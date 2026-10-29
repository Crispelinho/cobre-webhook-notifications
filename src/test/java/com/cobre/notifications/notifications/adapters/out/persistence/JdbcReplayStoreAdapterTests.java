package com.cobre.notifications.notifications.adapters.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.cobre.notifications.kernel.domain.ClientId;
import com.cobre.notifications.notifications.domain.DeliveryStatus;
import com.cobre.notifications.notifications.domain.NotificationEvent;
import com.cobre.notifications.notifications.domain.NotificationEventId;

/**
 * Each guard of the conditional replay write on its own, against a real PostgreSQL: the version guard (a stale
 * snapshot loses) and the status guard (a snapshot that is not {@code failed} loses even with the right version).
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(JdbcReplayStoreAdapter.class)
class JdbcReplayStoreAdapterTests {

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18.6"));

	private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

	private static final ClientId CLIENT = new ClientId("C1");

	private static final NotificationEventId ID = new NotificationEventId("N1");

	@Autowired
	private JdbcReplayStoreAdapter store;

	@Autowired
	private JdbcClient jdbc;

	@BeforeEach
	void insertFailedNotification() {
		jdbc.sql("INSERT INTO events (id, client_id, event_type, content, created_at) "
				+ "VALUES ('E1', 'C1', 'credit_deposit', 'the content', :now)")
			.param("now", Timestamp.from(T0))
			.update();
		jdbc.sql("INSERT INTO subscriptions (id, client_id, url, event_types, active, created_at) "
				+ "VALUES ('S1', 'C1', 'https://hooks.example.com/a', :types, true, :now)")
			.param("types", new String[] { "credit_deposit" })
			.param("now", Timestamp.from(T0))
			.update();
		insertFailedNotification("N1");
	}

	@Test
	void aStaleSnapshotLosesTheVersionGuardAndOnlyOneRoundIsStarted() {
		NotificationEvent stale = store.findById(CLIENT, ID).orElseThrow();
		assertThat(stale.deliveryStatus()).isEqualTo(DeliveryStatus.FAILED);
		NotificationEvent replayed = stale.replay();

		boolean first = store.startReplay(stale, replayed, T0);
		boolean second = store.startReplay(stale, replayed, T0);

		assertThat(first).isTrue();
		assertThat(second).isFalse();
		assertThat(roundTwoOutboxRows("N1")).hasSize(1);
		NotificationEvent stored = store.findById(CLIENT, ID).orElseThrow();
		assertThat(stored.round()).isEqualTo(2);
		assertThat(stored.version()).isEqualTo(stale.version() + 1);
	}

	@Test
	void aFailedNotificationOfAnInactiveSubscriptionStartsRoundTwo() {
		NotificationEvent failed = store.findById(CLIENT, ID).orElseThrow();
		jdbc.sql("UPDATE subscriptions SET active = false WHERE id = 'S1'").update();

		boolean started = store.startReplay(failed, failed.replay(), T0);

		assertThat(started).isTrue();
		assertThat(roundTwoOutboxRows("N1")).hasSize(1);
		assertThat(store.findById(CLIENT, ID).orElseThrow().round()).isEqualTo(2);
	}

	@Test
	void theStatusGuardRejectsASnapshotThatIsNotFailedEvenWithTheRightVersion() {
		NotificationEvent failed = store.findById(CLIENT, ID).orElseThrow();
		// Bypasses the domain check: a snapshot of the same row and version, but claiming to be completed.
		NotificationEvent notFailed = new NotificationEvent(failed.id(), failed.eventId(), failed.clientId(),
				failed.eventType(), failed.content(), DeliveryStatus.COMPLETED, failed.createdAt(),
				failed.deliveryDate(), failed.deliveredAt(), failed.lastError(), failed.round(), failed.version(),
				List.of());
		NotificationEvent after = new NotificationEvent(failed.id(), failed.eventId(), failed.clientId(),
				failed.eventType(), failed.content(), DeliveryStatus.PENDING, failed.createdAt(), null,
				failed.deliveredAt(), null, failed.round() + 1, failed.version(), List.of());
		// The row is completed in the database too, so only the status condition can reject the write.
		jdbc.sql("UPDATE notifications SET delivery_status = 'completed' WHERE id = 'N1'").update();

		assertThat(store.startReplay(notFailed, after, T0)).isFalse();

		assertThat(roundTwoOutboxRows("N1")).isEmpty();
		assertThat(jdbc.sql("SELECT round FROM notifications WHERE id = 'N1'").query(Integer.class).single())
			.isEqualTo(1);
	}

	@Test
	void theOutboxPayloadStaysValidJsonForAnIdWithQuotesAndBackslashes() {
		String awkward = "N\"2\\x";
		insertFailedNotification(awkward);
		NotificationEvent stale = store.findById(CLIENT, new NotificationEventId(awkward)).orElseThrow();

		assertThat(store.startReplay(stale, stale.replay(), T0)).isTrue();

		assertThat(jdbc.sql("SELECT payload ->> 'notification_event_id' FROM outbox WHERE notification_id = :id")
			.param("id", awkward)
			.query(String.class)
			.single()).isEqualTo(awkward);
		assertThat(jdbc.sql("SELECT (payload ->> 'round')::int FROM outbox WHERE notification_id = :id")
			.param("id", awkward)
			.query(Integer.class)
			.single()).isEqualTo(2);
	}

	private void insertFailedNotification(String id) {
		jdbc.sql("INSERT INTO notifications (id, event_id, subscription_id, client_id, destination_url, "
				+ "delivery_status, round, attempts, created_at, delivery_date, last_error) "
				+ "VALUES (:id, 'E1', 'S1', 'C1', 'https://hooks.example.com/a', 'failed', 1, 1, :now, :now, "
				+ "'http_500')")
			.param("id", id)
			.param("now", Timestamp.from(T0))
			.update();
	}

	private List<Integer> roundTwoOutboxRows(String id) {
		return jdbc.sql("SELECT round FROM outbox WHERE notification_id = :id AND round = 2")
			.param("id", id)
			.query(Integer.class)
			.list();
	}

}
