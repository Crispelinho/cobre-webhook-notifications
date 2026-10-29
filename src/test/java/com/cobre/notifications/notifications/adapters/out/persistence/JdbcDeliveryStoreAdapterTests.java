package com.cobre.notifications.notifications.adapters.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

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

import com.cobre.notifications.notifications.domain.AttemptResult;
import com.cobre.notifications.notifications.domain.Delivery;
import com.cobre.notifications.notifications.domain.DeliveryAttempt;
import com.cobre.notifications.notifications.domain.DeliveryStatus;
import com.cobre.notifications.notifications.domain.Lease;

/**
 * The conditional writes that make dispatch consumption idempotent, against a real PostgreSQL.
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(JdbcDeliveryStoreAdapter.class)
class JdbcDeliveryStoreAdapterTests {

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18.6"));

	private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

	private static final Instant T1 = Instant.parse("2026-01-01T00:00:05Z");

	@Autowired
	private JdbcDeliveryStoreAdapter store;

	@Autowired
	private JdbcClient jdbc;

	@BeforeEach
	void insertNotification() {
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

	@Test
	void findsTheDeliveryWithEventDataAndZeroAttempts() {
		Delivery delivery = store.find("N1").orElseThrow();

		assertThat(delivery.eventId()).isEqualTo("E1");
		assertThat(delivery.eventType()).isEqualTo("credit_deposit");
		assertThat(delivery.content()).isEqualTo("the content");
		assertThat(delivery.destinationUrl()).isEqualTo("https://hooks.example.com/a");
		assertThat(delivery.status()).isEqualTo(DeliveryStatus.PENDING);
		assertThat(delivery.attemptsInRound()).isZero();
		assertThat(delivery.version()).isZero();
		assertThat(store.find("MISSING")).isEmpty();
	}

	@Test
	void findCarriesTheSubscriptionIdAndTheStoredSecretCiphertextUntouched() {
		assertThat(store.find("N1").orElseThrow().subscriptionId()).isEqualTo("S1");
		assertThat(store.find("N1").orElseThrow().secretCiphertext()).isNull();

		byte[] ciphertext = { 1, 2, 3, 4 };
		jdbc.sql("UPDATE subscriptions SET secret_ciphertext = :secret WHERE id = 'S1'")
			.param("secret", ciphertext)
			.update();

		assertThat(store.find("N1").orElseThrow().secretCiphertext()).containsExactly(ciphertext);
	}

	@Test
	void recordingAnAttemptStoresTheRowAndTheNewStateTogether() {
		Delivery before = store.find("N1").orElseThrow();
		AttemptResult result = AttemptResult.response(T0, 200, 42);
		Delivery after = before.afterAttempt(result, T1);

		assertThat(store.recordAttempt(claim(before), before, attempt(before, result), after)).isTrue();

		Delivery stored = store.find("N1").orElseThrow();
		assertThat(stored.status()).isEqualTo(DeliveryStatus.COMPLETED);
		assertThat(stored.attemptsInRound()).isEqualTo(1);
		assertThat(stored.deliveredAt()).isEqualTo(T0);
		assertThat(stored.deliveryDate()).isEqualTo(T1);
		assertThat(stored.version()).isEqualTo(1);
		assertThat(jdbc.sql("SELECT attempts FROM notifications WHERE id = 'N1'").query(Integer.class).single())
			.isEqualTo(1);
		assertThat(jdbc.sql("SELECT http_status FROM delivery_attempts WHERE notification_id = 'N1'")
			.query(Integer.class)
			.single()).isEqualTo(200);
	}

	@Test
	void aSecondWriterWithTheSameVersionLosesAndRecordsNothing() {
		Delivery before = store.find("N1").orElseThrow();
		AttemptResult result = AttemptResult.response(T0, 503, 42);
		Delivery after = before.afterAttempt(result, T1);
		DeliveryAttempt attempt = attempt(before, result);
		Lease lease = claim(before);

		assertThat(store.recordAttempt(lease, before, attempt, after)).isTrue();
		assertThat(store.recordAttempt(lease, before, attempt, after)).isFalse();

		assertThat(jdbc.sql("SELECT count(*) FROM delivery_attempts WHERE notification_id = 'N1'")
			.query(Long.class)
			.single()).isEqualTo(1);
		assertThat(jdbc.sql("SELECT attempts FROM notifications WHERE id = 'N1'").query(Integer.class).single())
			.isEqualTo(1);
		assertThat(store.find("N1").orElseThrow().status()).isEqualTo(DeliveryStatus.RETRYING);
	}

	@Test
	void aFinalNotificationEventTakesNoMoreWrites() {
		Delivery pending = store.find("N1").orElseThrow();
		AttemptResult ok = AttemptResult.response(T0, 200, 1);
		store.recordAttempt(claim(pending), pending, attempt(pending, ok), pending.afterAttempt(ok, T1));
		Delivery completed = store.find("N1").orElseThrow();
		// Same version as stored, so only the final status can be what rejects the write.
		Delivery forged = new Delivery(completed.id(), completed.eventId(), completed.eventType(), completed.content(),
				completed.destinationUrl(), completed.createdAt(), DeliveryStatus.PENDING, completed.round(), 0, null,
				null, null, completed.version());

		// A lease that was valid when it was taken; only the final status can be what rejects the write.
		assertThat(store.updateState(new Lease("w", 1), forged, forged.exhausted(T1))).isFalse();

		assertThat(store.find("N1").orElseThrow().status()).isEqualTo(DeliveryStatus.COMPLETED);
	}

	@Test
	void aWriteForAnotherRoundIsRejected() {
		Delivery before = store.find("N1").orElseThrow();
		Lease lease = claim(before);
		jdbc.sql("UPDATE notifications SET round = 2 WHERE id = 'N1'").update();

		assertThat(store.updateState(lease, before, before.exhausted(T1))).isFalse();
	}

	@Test
	void attemptsAreCountedPerRound() {
		Delivery before = store.find("N1").orElseThrow();
		AttemptResult failure = AttemptResult.response(T0, 500, 1);
		store.recordAttempt(claim(before), before, attempt(before, failure), before.afterAttempt(failure, T1));
		jdbc.sql("UPDATE notifications SET round = 2, delivery_status = 'pending' WHERE id = 'N1'").update();

		assertThat(store.find("N1").orElseThrow().attemptsInRound()).isZero();
	}

	private Lease claim(Delivery delivery) {
		return store.claim(delivery, "worker", T0, Duration.ofSeconds(60)).orElseThrow();
	}

	private static DeliveryAttempt attempt(Delivery delivery, AttemptResult result) {
		return new DeliveryAttempt(delivery.round(), delivery.nextAttemptNumber(), result.attemptedAt(),
				result.httpStatus(), result.errorType(), result.latencyMs());
	}

}
