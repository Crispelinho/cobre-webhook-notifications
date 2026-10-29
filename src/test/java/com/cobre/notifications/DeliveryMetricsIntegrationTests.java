package com.cobre.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The outbox-age gauge reads the real outbox table, and the delivery meters are registered in the application's
 * registry. The relay is off so the rows stay unpublished for as long as the test needs.
 */
@SpringBootTest(properties = { "app.seed.enabled=true", "app.outbox.relay.enabled=false",
		"test.isolated-database=metrics-integration" })
@Import(TestcontainersConfiguration.class)
class DeliveryMetricsIntegrationTests {

	private static final String AGE = "notification.outbox.oldest_unpublished.age";

	@Autowired
	private MeterRegistry registry;

	@Autowired
	private JdbcClient jdbc;

	@AfterEach
	void clean() {
		jdbc.sql("DELETE FROM outbox").update();
	}

	@Test
	void theDeliveryMetersAreRegisteredOnStartup() {
		assertThat(registry.get("notification.delivery.attempts").tag("outcome", "success").counter()).isNotNull();
		assertThat(registry.get("notification.delivery.attempt.duration").timers()).hasSize(3);
		assertThat(registry.get("notification.delivery.end_to_end.duration").timers()).hasSize(2);
		assertThat(registry.get("notification.dead_letter").counter().count()).isZero();
		assertThat(registry.get(AGE).gauge()).isNotNull();
	}

	@Test
	void anEmptyOutboxHasAgeZero() {
		assertThat(registry.get(AGE).gauge().value()).isZero();
	}

	@Test
	void theGaugeShowsTheAgeOfTheOldestUnpublishedRowAndIgnoresPublishedRows() {
		jdbc.sql("INSERT INTO outbox (notification_id, round, payload, created_at, published_at) "
				+ "VALUES ('EVT001', 1, '{}', :created, :published)")
			.param("created", ago(600))
			.param("published", ago(540))
			.update();
		assertThat(registry.get(AGE).gauge().value()).isZero();

		insertUnpublished("EVT002", 120);
		insertUnpublished("EVT003", 5);

		assertThat(registry.get(AGE).gauge().value()).isBetween(119.0, 150.0);
	}

	@Test
	void theGaugeDropsBackToZeroOnceTheRowIsPublished() {
		insertUnpublished("EVT002", 120);
		assertThat(registry.get(AGE).gauge().value()).isGreaterThan(60);

		jdbc.sql("UPDATE outbox SET published_at = now()").update();

		assertThat(registry.get(AGE).gauge().value()).isZero();
	}

	/**
	 * Timestamps come from the JVM clock, the same one the gauge uses; the database container's clock may drift.
	 */
	private static Timestamp ago(long seconds) {
		return Timestamp.from(Instant.now().minusSeconds(seconds));
	}

	private void insertUnpublished(String notificationId, long ageSeconds) {
		jdbc.sql("INSERT INTO outbox (notification_id, round, payload, created_at) "
				+ "VALUES (:id, 1, '{}', :created)")
			.param("id", notificationId)
			.param("created", ago(ageSeconds))
			.update();
	}

}
