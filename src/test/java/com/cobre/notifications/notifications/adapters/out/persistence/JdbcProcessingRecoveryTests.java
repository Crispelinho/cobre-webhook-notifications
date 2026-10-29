package com.cobre.notifications.notifications.adapters.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
import com.cobre.notifications.notifications.application.port.out.LeaseRecoveryStore.Recovered;
import com.cobre.notifications.notifications.domain.AttemptResult;
import com.cobre.notifications.notifications.domain.Delivery;
import com.cobre.notifications.notifications.domain.DeliveryAttempt;
import com.cobre.notifications.notifications.domain.DeliveryStatus;
import com.cobre.notifications.notifications.domain.Lease;

/**
 * Lease, claim, fenced writes and recovery against a real PostgreSQL. The tests commit for real (no wrapping
 * transaction) because the concurrency scenarios need several connections.
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(JdbcDeliveryStoreAdapter.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class JdbcProcessingRecoveryTests {

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18.6"));

	private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

	private static final Duration LEASE = Duration.ofSeconds(60);

	private static final Instant AFTER_EXPIRY = T0.plus(LEASE).plusSeconds(1);

	@Autowired
	private JdbcDeliveryStoreAdapter store;

	@Autowired
	private JdbcClient jdbc;

	@BeforeEach
	void insertNotification() {
		cleanUp();
		jdbc.sql("INSERT INTO events (id, client_id, event_type, content, created_at) "
				+ "VALUES ('E1', 'C1', 'credit_deposit', 'the content', :now)")
			.param("now", Timestamp.from(T0))
			.update();
		jdbc.sql("INSERT INTO subscriptions (id, client_id, url, event_types, active, created_at) "
				+ "VALUES ('S1', 'C1', 'https://hooks.example.com/a', :types, true, :now)")
			.param("types", new String[] { "credit_deposit" })
			.param("now", Timestamp.from(T0))
			.update();
		jdbc.sql("INSERT INTO notifications (id, event_id, subscription_id, client_id, destination_url, "
				+ "delivery_status, round, attempts, created_at) "
				+ "VALUES ('N1', 'E1', 'S1', 'C1', 'https://hooks.example.com/a', 'pending', 1, 0, :now)")
			.param("now", Timestamp.from(T0))
			.update();
	}

	@AfterEach
	void cleanUp() {
		jdbc.sql("DELETE FROM outbox").update();
		jdbc.sql("DELETE FROM delivery_attempts").update();
		jdbc.sql("DELETE FROM notifications").update();
		jdbc.sql("DELETE FROM subscriptions").update();
		jdbc.sql("DELETE FROM events").update();
	}

	@Test
	void aClaimTakesTheLeaseAndIncreasesTheGenerationToken() {
		Delivery observed = store.find("N1").orElseThrow();

		Lease lease = store.claim(observed, "worker-a", T0, LEASE).orElseThrow();

		assertThat(lease.owner()).isEqualTo("worker-a");
		assertThat(lease.generation()).isEqualTo(1);
		Map<String, Object> row = jdbc.sql("SELECT lease_owner, lease_expires_at, generation FROM notifications "
				+ "WHERE id = 'N1'")
			.query()
			.singleRow();
		assertThat(row.get("lease_owner")).isEqualTo("worker-a");
		assertThat(row.get("generation")).isEqualTo(1L);
		assertThat(jdbc.sql("SELECT lease_expires_at FROM notifications WHERE id = 'N1'")
			.query(java.time.OffsetDateTime.class)
			.single()
			.toInstant()).isEqualTo(T0.plus(LEASE));
	}

	@Test
	void aNotificationEventWithAnUnexpiredLeaseCannotBeClaimedByAnotherWorker() {
		Delivery observed = store.find("N1").orElseThrow();
		store.claim(observed, "worker-a", T0, LEASE).orElseThrow();

		Optional<Lease> second = store.claim(observed, "worker-b", T0.plusSeconds(59), LEASE);

		assertThat(second).isEmpty();
		assertThat(jdbc.sql("SELECT lease_owner FROM notifications WHERE id = 'N1'").query(String.class).single())
			.isEqualTo("worker-a");
	}

	@Test
	void twoWorkersConsumingTheSameMessageSendExactlyOneRequest() throws Exception {
		AtomicInteger requests = new AtomicInteger();
		CountDownLatch firstIsSending = new CountDownLatch(1);
		CountDownLatch secondIsDone = new CountDownLatch(1);
		Clock clock = Clock.fixed(T0, ZoneOffset.UTC);
		DeliveryService first = new DeliveryService(store, delivery -> {
			requests.incrementAndGet();
			firstIsSending.countDown();
			awaitQuietly(secondIsDone);
			return AttemptResult.response(T0, 200, 5);
		}, clock, 5);
		DeliveryService second = new DeliveryService(store, delivery -> {
			requests.incrementAndGet();
			return AttemptResult.response(T0, 200, 5);
		}, clock, 5);
		DispatchMessage message = new DispatchMessage("N1", 1);

		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			Future<DeliveryResult> firstResult = pool.submit(() -> first.deliver(message));
			assertThat(firstIsSending.await(10, TimeUnit.SECONDS)).isTrue();
			// The first worker is inside the send and holds the lease; the second consumes the same message now.
			DeliveryResult secondResult = pool.submit(() -> second.deliver(message)).get(10, TimeUnit.SECONDS);
			secondIsDone.countDown();

			assertThat(secondResult).isEqualTo(DeliveryResult.IGNORED);
			assertThat(firstResult.get(10, TimeUnit.SECONDS)).isEqualTo(DeliveryResult.DELIVERED);
		}
		finally {
			secondIsDone.countDown();
			pool.shutdownNow();
		}

		assertThat(requests).hasValue(1);
		assertThat(attemptRows()).hasSize(1);
		assertThat(store.find("N1").orElseThrow().status()).isEqualTo(DeliveryStatus.COMPLETED);
	}

	@Test
	void anExpiredLeaseIsReclaimedExactlyOnceUnderConcurrency() throws Exception {
		Delivery observed = store.find("N1").orElseThrow();
		store.claim(observed, "crashed-worker", T0, LEASE).orElseThrow();
		int workers = 8;
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(workers);
		List<Future<Optional<Lease>>> results = new ArrayList<>();
		try {
			for (int i = 0; i < workers; i++) {
				String owner = "worker-" + i;
				Callable<Optional<Lease>> claim = () -> {
					start.await();
					return store.claim(observed, owner, AFTER_EXPIRY, LEASE);
				};
				results.add(pool.submit(claim));
			}
			start.countDown();

			List<Lease> winners = new ArrayList<>();
			for (Future<Optional<Lease>> result : results) {
				result.get(20, TimeUnit.SECONDS).ifPresent(winners::add);
			}

			assertThat(winners).hasSize(1);
			assertThat(winners.get(0).generation()).isEqualTo(2);
		}
		finally {
			pool.shutdownNow();
		}
		// The abandoned attempt of the crashed worker was recorded once, not once per contender.
		assertThat(attemptRows()).singleElement().satisfies(row -> assertThat(row.get("error_type")).isEqualTo("interrupted"));
	}

	@Test
	void aStaleWorkerCannotOverwriteANewerResult() {
		Delivery observedByA = store.find("N1").orElseThrow();
		Lease leaseA = store.claim(observedByA, "worker-a", T0, LEASE).orElseThrow();

		// A is slow: its lease expires and worker B reclaims and completes the delivery.
		Delivery observedByB = store.find("N1").orElseThrow();
		Lease leaseB = store.claim(observedByB, "worker-b", AFTER_EXPIRY, LEASE).orElseThrow();
		Delivery readByB = store.find("N1").orElseThrow();
		AttemptResult ok = AttemptResult.response(AFTER_EXPIRY, 200, 10);
		assertThat(store.recordAttempt(leaseB, readByB, attempt(readByB, ok), readByB.afterAttempt(ok, AFTER_EXPIRY)))
			.isTrue();

		// A finally gets its (failed) answer and tries to write it.
		AttemptResult late = AttemptResult.response(T0, 503, 10);
		boolean written = store.recordAttempt(leaseA, observedByA, attempt(observedByA, late),
				observedByA.afterAttempt(late, AFTER_EXPIRY.plusSeconds(5)));

		assertThat(written).isFalse();
		Delivery stored = store.find("N1").orElseThrow();
		assertThat(stored.status()).isEqualTo(DeliveryStatus.COMPLETED);
		assertThat(stored.deliveredAt()).isEqualTo(AFTER_EXPIRY);
		assertThat(attemptRows()).extracting(row -> row.get("error_type")).containsExactly("interrupted", null);
		assertThat(attemptRows()).extracting(row -> row.get("http_status")).containsExactly(null, 200);
	}

	@Test
	void aWorkerWhoseLeaseWasReleasedByRecoveryIsFencedEvenBeforeAnyReclaim() {
		Delivery observed = store.find("N1").orElseThrow();
		Lease lease = store.claim(observed, "worker-a", T0, LEASE).orElseThrow();
		store.recoverExpired(AFTER_EXPIRY, 5, 10);

		// Reads the current version, so only the generation token can reject the stale worker's write.
		Delivery current = store.find("N1").orElseThrow();
		AttemptResult late = AttemptResult.response(T0, 503, 10);
		boolean written = store.recordAttempt(lease, current, attempt(current, late),
				current.afterAttempt(late, AFTER_EXPIRY));

		assertThat(written).isFalse();
		assertThat(attemptRows()).singleElement().satisfies(row -> assertThat(row.get("error_type")).isEqualTo("interrupted"));
	}

	@Test
	void recoveryKeepsTheInterruptedAttemptAndDispatchesTheEventAgain() {
		Delivery observed = store.find("N1").orElseThrow();
		store.claim(observed, "worker-a", T0, LEASE).orElseThrow();

		List<Recovered> recovered = store.recoverExpired(AFTER_EXPIRY, 5, 10);

		assertThat(recovered).containsExactly(new Recovered("N1", 1, false));
		assertThat(attemptRows()).singleElement().satisfies(row -> {
			assertThat(row.get("attempt_number")).isEqualTo(1);
			assertThat(row.get("error_type")).isEqualTo("interrupted");
			assertThat(row.get("http_status")).isNull();
		});
		Delivery stored = store.find("N1").orElseThrow();
		assertThat(stored.status()).isEqualTo(DeliveryStatus.RETRYING);
		assertThat(stored.attemptsInRound()).isEqualTo(1);
		assertThat(jdbc.sql("SELECT lease_expires_at FROM notifications WHERE id = 'N1'")
			.query(java.time.OffsetDateTime.class)
			.optional()).isEmpty();
		assertThat(jdbc.sql("SELECT count(*) FROM outbox WHERE notification_id = 'N1' AND round = 1")
			.query(Long.class)
			.single()).isEqualTo(1);
		// The released event can be claimed again by a new worker.
		assertThat(store.claim(stored, "worker-b", AFTER_EXPIRY, LEASE)).isPresent();
	}

	@Test
	void anUnexpiredLeaseIsNotRecovered() {
		Delivery observed = store.find("N1").orElseThrow();
		store.claim(observed, "worker-a", T0, LEASE).orElseThrow();

		List<Recovered> recovered = store.recoverExpired(T0.plusSeconds(59), 5, 10);

		assertThat(recovered).isEmpty();
		assertThat(attemptRows()).isEmpty();
		assertThat(jdbc.sql("SELECT count(*) FROM outbox").query(Long.class).single()).isZero();
		assertThat(jdbc.sql("SELECT lease_owner FROM notifications WHERE id = 'N1'").query(String.class).single())
			.isEqualTo("worker-a");
	}

	@Test
	void theAttemptHistorySurvivesAReclaim() {
		// Two attempts already recorded in round 1.
		for (int number = 1; number <= 2; number++) {
			Delivery before = store.find("N1").orElseThrow();
			Lease lease = store.claim(before, "worker-" + number, T0.plusSeconds(number * 100L), LEASE).orElseThrow();
			Delivery read = store.find("N1").orElseThrow();
			AttemptResult failure = AttemptResult.response(T0.plusSeconds(number * 100L), 503, 10 + number);
			assertThat(store.recordAttempt(lease, read, attempt(read, failure),
					read.afterAttempt(failure, T0.plusSeconds(number * 100L))))
				.isTrue();
		}
		List<Map<String, Object>> before = attemptRows();
		assertThat(before).hasSize(2);

		// The third attempt is interrupted and recovered, then claimed and answered by another worker.
		Delivery third = store.find("N1").orElseThrow();
		store.claim(third, "worker-3", T0.plusSeconds(300), LEASE).orElseThrow();
		store.recoverExpired(T0.plusSeconds(300).plus(LEASE).plusSeconds(1), 5, 10);

		List<Map<String, Object>> after = attemptRows();
		assertThat(after).hasSize(3);
		assertThat(after.subList(0, 2)).isEqualTo(before);
		assertThat(after.get(2).get("attempt_number")).isEqualTo(3);
		assertThat(after.get(2).get("error_type")).isEqualTo("interrupted");
	}

	@Test
	void anInterruptedLastAttemptFailsTheRoundWithoutDispatchingAgain() {
		jdbc.sql("UPDATE notifications SET delivery_status = 'retrying', attempts = 4 WHERE id = 'N1'").update();
		for (int number = 1; number <= 4; number++) {
			jdbc.sql("INSERT INTO delivery_attempts (notification_id, round, attempt_number, attempted_at, "
					+ "http_status) VALUES ('N1', 1, :number, :at, 503)")
				.param("number", number)
				.param("at", Timestamp.from(T0))
				.update();
		}
		Delivery observed = store.find("N1").orElseThrow();
		store.claim(observed, "worker-a", T0, LEASE).orElseThrow();

		List<Recovered> recovered = store.recoverExpired(AFTER_EXPIRY, 5, 10);

		assertThat(recovered).containsExactly(new Recovered("N1", 1, true));
		Delivery stored = store.find("N1").orElseThrow();
		assertThat(stored.status()).isEqualTo(DeliveryStatus.FAILED);
		assertThat(stored.deliveryDate()).isEqualTo(AFTER_EXPIRY);
		assertThat(stored.lastError()).isEqualTo("interrupted");
		assertThat(jdbc.sql("SELECT count(*) FROM outbox").query(Long.class).single()).isZero();
	}

	private List<Map<String, Object>> attemptRows() {
		return jdbc.sql("SELECT attempt_number, http_status, error_type, latency_ms, attempted_at "
				+ "FROM delivery_attempts WHERE notification_id = 'N1' ORDER BY attempt_number")
			.query()
			.listOfRows();
	}

	private static DeliveryAttempt attempt(Delivery delivery, AttemptResult result) {
		return new DeliveryAttempt(delivery.round(), delivery.nextAttemptNumber(), result.attemptedAt(),
				result.httpStatus(), result.errorType(), result.latencyMs());
	}

	private static void awaitQuietly(CountDownLatch latch) {
		try {
			latch.await(10, TimeUnit.SECONDS);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

}
