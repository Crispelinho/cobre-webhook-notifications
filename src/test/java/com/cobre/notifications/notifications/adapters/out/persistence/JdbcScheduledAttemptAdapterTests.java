package com.cobre.notifications.notifications.adapters.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.cobre.notifications.notifications.application.DeliveryResult;
import com.cobre.notifications.notifications.application.DeliveryService;
import com.cobre.notifications.notifications.application.DispatchMessage;
import com.cobre.notifications.notifications.application.port.out.DeliveryMetrics;
import com.cobre.notifications.notifications.domain.AttemptResult;
import com.cobre.notifications.notifications.domain.Delivery;
import com.cobre.notifications.notifications.domain.DeliveryAttempt;
import com.cobre.notifications.notifications.domain.DeliveryStatus;
import com.cobre.notifications.notifications.domain.Lease;

/**
 * The Retry-After path against a real PostgreSQL: the next attempt time is stored with the attempt, and due rows are
 * enqueued exactly once even when several schedulers poll at the same time. Data is committed (no test transaction)
 * because concurrent claims need separate connections.
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({ JdbcDeliveryStoreAdapter.class, JdbcScheduledAttemptAdapter.class })
class JdbcScheduledAttemptAdapterTests {

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18.6"));

	private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

	@Autowired
	private JdbcDeliveryStoreAdapter store;

	@Autowired
	private JdbcScheduledAttemptAdapter scheduled;

	@Autowired
	private JdbcClient jdbc;

	@BeforeEach
	void insertEventAndSubscription() {
		jdbc.sql("INSERT INTO events (id, client_id, event_type, content, created_at) "
				+ "VALUES ('E1', 'C1', 'credit_deposit', 'the content', :now)")
			.param("now", Timestamp.from(T0))
			.update();
		jdbc.sql("INSERT INTO subscriptions (id, client_id, url, event_types, active, created_at) "
				+ "VALUES ('S1', 'C1', 'https://hooks.example.com/a', :types, true, :now)")
			.param("types", new String[] { "credit_deposit" })
			.param("now", Timestamp.from(T0))
			.update();
	}

	@AfterEach
	void clean() {
		jdbc.sql("DELETE FROM outbox").update();
		jdbc.sql("DELETE FROM delivery_attempts").update();
		jdbc.sql("DELETE FROM notifications").update();
		jdbc.sql("DELETE FROM subscriptions").update();
		jdbc.sql("DELETE FROM events").update();
	}

	@Test
	void recordingAnAttemptStoresTheDatabaseClockPlusTheDelayAndTheNextWriteClearsIt() {
		insertNotification("N1", "pending", 1, null);
		Delivery before = store.find("N1").orElseThrow();
		AttemptResult result = AttemptResult.response(T0, 429, 5, Duration.ofSeconds(60));
		Delivery after = before.afterAttempt(result, T0);
		DeliveryAttempt attempt = new DeliveryAttempt(1, 1, T0, 429, "http_429", 5);

		assertThat(store.recordAttempt(claim(before), before, attempt, after, Duration.ofSeconds(60))).isTrue();

		Delivery waiting = store.find("N1").orElseThrow();
		assertThat(waiting.status()).isEqualTo(DeliveryStatus.RETRYING);
		assertThat(waiting.attemptsInRound()).isEqualTo(1);
		// Written at the database now() + 60 s: after (now() + 59 s) and not past (now() + 60 s) the later now().
		assertThat(jdbc.sql("SELECT next_attempt_at > now() + interval '59 seconds' "
				+ "AND next_attempt_at <= now() + interval '60 seconds' FROM notifications WHERE id = 'N1'")
			.query(Boolean.class)
			.single()).isTrue();

		// Any later write (here: retries exhausted) leaves no stale time behind.
		assertThat(store.updateState(claim(waiting), waiting, waiting.exhausted(T0))).isTrue();
		assertThat(store.find("N1").orElseThrow().nextAttemptAt()).isNull();
	}

	@Test
	void aRetryAfterOf3600SecondsIsStoredNoMoreThan300SecondsAfterNow() {
		insertNotification("N1", "pending", 1, null);
		DeliveryService service = new DeliveryService(store,
				delivery -> AttemptResult.response(T0, 429, 5, Duration.ofSeconds(3600)), Clock.fixed(T0, ZoneOffset.UTC),
				5, DeliveryMetrics.NONE, Duration.ofSeconds(60), Duration.ofSeconds(300));

		assertThat(service.deliver(new DispatchMessage("N1", 1))).isEqualTo(DeliveryResult.SCHEDULED);

		assertThat(jdbc.sql("SELECT next_attempt_at <= now() + interval '300 seconds' "
				+ "AND next_attempt_at > now() + interval '299 seconds' FROM notifications WHERE id = 'N1'")
			.query(Boolean.class)
			.single()).isTrue();
	}

	@Test
	void aScheduledAttemptIsEnqueuedOnlyBySchedulerAndNeverSentDirectly() {
		insertNotification("N1", "pending", 1, null);
		java.util.concurrent.atomic.AtomicInteger sent = new java.util.concurrent.atomic.AtomicInteger();
		DeliveryService service = new DeliveryService(store, delivery -> {
			sent.incrementAndGet();
			return AttemptResult.response(T0, 429, 5, Duration.ofSeconds(1));
		}, Clock.fixed(T0, ZoneOffset.UTC), 5, DeliveryMetrics.NONE, Duration.ofSeconds(60), Duration.ofSeconds(300));

		assertThat(service.deliver(new DispatchMessage("N1", 1))).isEqualTo(DeliveryResult.SCHEDULED);
		// A duplicate message does not bypass the claim.
		assertThat(service.deliver(new DispatchMessage("N1", 1))).isEqualTo(DeliveryResult.IGNORED);
		assertThat(sent).hasValue(1);

		jdbc.sql("UPDATE notifications SET next_attempt_at = now() - interval '1 second' WHERE id = 'N1'").update();
		assertThat(scheduled.enqueueDue(100)).isEqualTo(1);
		assertThat(scheduled.enqueueDue(100)).isZero();
	}

	@Test
	void releasingAScheduleClearsTheTimeAndEnqueuesTheOutboxRowTogether() {
		insertNotification("N1", "retrying", 1, -1);

		assertThat(scheduled.enqueueDue(100)).isEqualTo(1);

		assertThat(store.find("N1").orElseThrow().nextAttemptAt()).isNull();
		assertThat(jdbc.sql("SELECT count(*) FROM outbox WHERE notification_id = 'N1'").query(Long.class).single())
			.isEqualTo(1);
	}

	@Test
	void ifTheEnqueueFailsTheScheduleIsNotCleared() {
		insertNotification("N1", "retrying", 1, -1);
		jdbc.sql("CREATE FUNCTION fail_outbox_insert() RETURNS trigger LANGUAGE plpgsql AS "
				+ "$$ BEGIN RAISE EXCEPTION 'forced outbox failure'; END $$").update();
		jdbc.sql("CREATE TRIGGER fail_outbox BEFORE INSERT ON outbox FOR EACH ROW EXECUTE FUNCTION fail_outbox_insert()")
			.update();
		try {
			org.assertj.core.api.Assertions.assertThatThrownBy(() -> scheduled.enqueueDue(100))
				.isInstanceOf(RuntimeException.class);
		}
		finally {
			jdbc.sql("DROP TRIGGER fail_outbox ON outbox").update();
			jdbc.sql("DROP FUNCTION fail_outbox_insert()").update();
		}

		assertThat(store.find("N1").orElseThrow().nextAttemptAt()).isNotNull();
		assertThat(jdbc.sql("SELECT count(*) FROM outbox").query(Long.class).single()).isZero();
		// Once the fault is gone the same row is released normally.
		assertThat(scheduled.enqueueDue(100)).isEqualTo(1);
	}

	@Test
	void aMessageWhileTheTimeIsSetIsIgnoredAndTheMessageTheSchedulerEnqueuedIsProcessedAndSendsOnce() {
		insertNotification("N1", "pending", 1, null);
		java.util.concurrent.atomic.AtomicInteger sent = new java.util.concurrent.atomic.AtomicInteger();
		DeliveryService service = new DeliveryService(store, delivery -> sent.incrementAndGet() == 1
				? AttemptResult.response(T0, 429, 5, Duration.ofSeconds(1)) : AttemptResult.response(T0, 200, 5),
				Clock.fixed(T0, ZoneOffset.UTC), 5, DeliveryMetrics.NONE, Duration.ofSeconds(60), Duration.ofSeconds(300));
		DispatchMessage message = new DispatchMessage("N1", 1);

		assertThat(service.deliver(message)).isEqualTo(DeliveryResult.SCHEDULED);
		Instant scheduledTime = store.find("N1").orElseThrow().nextAttemptAt();

		// 1. While the time is set, a message is ignored and the time does not move.
		assertThat(service.deliver(message)).isEqualTo(DeliveryResult.IGNORED);
		assertThat(sent).hasValue(1);
		assertThat(store.find("N1").orElseThrow().nextAttemptAt()).isEqualTo(scheduledTime);

		// 2. After the release, the message it enqueued is processed and sends once.
		jdbc.sql("UPDATE notifications SET next_attempt_at = now() - interval '1 second' WHERE id = 'N1'").update();
		assertThat(scheduled.enqueueDue(100)).isEqualTo(1);
		assertThat(service.deliver(message)).isEqualTo(DeliveryResult.DELIVERED);
		assertThat(sent).hasValue(2);
		assertThat(store.find("N1").orElseThrow().status()).isEqualTo(DeliveryStatus.COMPLETED);
	}

	@Test
	void aDuplicateMessageRacingTheReleaseSendsAtMostOneRequestForTheRound() throws Exception {
		for (int run = 0; run < 20; run++) {
			String id = "R" + run;
			insertNotification(id, "pending", 1, null);
			java.util.concurrent.atomic.AtomicInteger sent = new java.util.concurrent.atomic.AtomicInteger();
			DeliveryService service = new DeliveryService(store, delivery -> sent.incrementAndGet() == 1
					? AttemptResult.response(T0, 429, 5, Duration.ofSeconds(1)) : AttemptResult.response(T0, 200, 5),
					Clock.fixed(T0, ZoneOffset.UTC), 5, DeliveryMetrics.NONE, Duration.ofSeconds(60), Duration.ofSeconds(300));
			DispatchMessage message = new DispatchMessage(id, 1);
			assertThat(service.deliver(message)).isEqualTo(DeliveryResult.SCHEDULED);
			jdbc.sql("UPDATE notifications SET next_attempt_at = now() - interval '1 second' WHERE id = :id")
				.param("id", id)
				.update();

			// One consumer handles the messages of a notification event in order (Kafka key = partition); the
			// scheduler releases from another thread at the same moment as the duplicate is handled.
			CountDownLatch start = new CountDownLatch(1);
			ExecutorService pool = Executors.newFixedThreadPool(2);
			try {
				Future<?> release = pool.submit(() -> {
					start.await();
					return scheduled.enqueueDue(100);
				});
				Future<?> consumer = pool.submit(() -> {
					start.await();
					service.deliver(message);
					return service.deliver(message);
				});
				start.countDown();
				release.get();
				consumer.get();
			}
			finally {
				pool.shutdownNow();
			}
			// The message the release enqueued is delivered after it, in order.
			service.deliver(message);

			// The 429 was request 1; at most one more request happens for the round.
			assertThat(sent.get()).isLessThanOrEqualTo(2);
			assertThat(jdbc.sql("SELECT count(*) FROM delivery_attempts WHERE notification_id = :id")
				.param("id", id)
				.query(Long.class)
				.single()).isLessThanOrEqualTo(2);
		}
	}

	@Test
	void aRowIsNotDueBeforeTheDatabaseClockReachesItAndIsDueOnceItHasPassed() {
		insertNotification("N1", "retrying", 1, 300);

		assertThat(scheduled.enqueueDue(100)).isZero();
		assertThat(jdbc.sql("SELECT count(*) FROM outbox").query(Long.class).single()).isZero();
		assertThat(store.find("N1").orElseThrow().nextAttemptAt()).isNotNull();

		jdbc.sql("UPDATE notifications SET next_attempt_at = now() - interval '1 second' WHERE id = 'N1'").update();

		assertThat(scheduled.enqueueDue(100)).isEqualTo(1);
		assertThat(store.find("N1").orElseThrow().nextAttemptAt()).isNull();
		// Polling again finds nothing: the claim cleared the time.
		assertThat(scheduled.enqueueDue(100)).isZero();
	}

	@Test
	void aDueRowIsEnqueuedForItsCurrentRound() {
		insertNotification("N1", "retrying", 2, -1);

		assertThat(scheduled.enqueueDue(100)).isEqualTo(1);

		assertThat(jdbc.sql("SELECT payload ->> 'notification_event_id' || ':' || (payload ->> 'round') "
				+ "FROM outbox WHERE notification_id = 'N1' AND published_at IS NULL AND round = 2")
			.query(String.class)
			.single()).isEqualTo("N1:2");
	}

	@Test
	void aClosedRowIsLeftAlone() {
		insertNotification("DONE", "completed", 1, -1);

		assertThat(scheduled.enqueueDue(100)).isZero();

		assertThat(jdbc.sql("SELECT count(*) FROM outbox").query(Long.class).single()).isZero();
	}

	@Test
	void concurrentSchedulersEnqueueEachDueRowExactlyOnce() throws Exception {
		int rows = 60;
		for (int i = 0; i < rows; i++) {
			insertNotification("N" + i, "retrying", 1, -1);
		}
		int schedulers = 6;
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(schedulers);
		try {
			List<Future<Integer>> results = new ArrayList<>();
			for (int s = 0; s < schedulers; s++) {
				results.add(pool.submit(() -> {
					start.await();
					int enqueued = 0;
					int batch;
					do {
						batch = scheduled.enqueueDue(7);
						enqueued += batch;
					}
					while (batch > 0);
					return enqueued;
				}));
			}
			start.countDown();
			int total = 0;
			for (Future<Integer> result : results) {
				total += result.get();
			}
			assertThat(total).isEqualTo(rows);
		}
		finally {
			pool.shutdownNow();
		}

		assertThat(jdbc.sql("SELECT count(*) FROM outbox").query(Long.class).single()).isEqualTo(rows);
		assertThat(jdbc.sql("SELECT count(DISTINCT notification_id) FROM outbox").query(Long.class).single())
			.isEqualTo(rows);
	}

	private Lease claim(Delivery delivery) {
		return store.claim(delivery, "worker-1", T0, Duration.ofSeconds(60)).orElseThrow();
	}

	/** @param dueInSeconds seconds from the database now() until the next attempt; null for none */
	private void insertNotification(String id, String status, int round, Integer dueInSeconds) {
		jdbc.sql("INSERT INTO notifications (id, event_id, subscription_id, client_id, destination_url, "
				+ "delivery_status, round, attempts, created_at, next_attempt_at) "
				+ "VALUES (:id, 'E1', 'S1', 'C1', 'https://hooks.example.com/a', :status, :round, 0, :now, "
				+ "CASE WHEN CAST(:due AS integer) IS NULL THEN NULL "
				+ "ELSE now() + make_interval(secs => CAST(:due AS integer)) END)")
			.param("id", id)
			.param("status", status)
			.param("round", round)
			.param("now", Timestamp.from(T0))
			.param("due", dueInSeconds)
			.update();
	}

}
