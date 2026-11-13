package com.cobre.notifications.notifications.adapters.out.metrics;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Age in seconds of the oldest unpublished outbox row, or 0 when the outbox is empty. It is read from the database
 * whenever the registry is sampled, so it is correct on every instance and also while the relay is stopped or the
 * broker is down (the case the alert exists for). The query follows the partial index of unpublished rows.
 */
@Component
class OutboxAgeGauge {

	static final String NAME = "notification.outbox.oldest_unpublished.age";

	private static final Logger log = LoggerFactory.getLogger(OutboxAgeGauge.class);

	private final JdbcClient jdbc;

	OutboxAgeGauge(JdbcClient jdbc, MeterRegistry registry) {
		this.jdbc = jdbc;
		Gauge.builder(NAME, this, OutboxAgeGauge::ageSeconds)
			.description("Age of the oldest outbox row not yet published to Kafka")
			.baseUnit("seconds")
			.register(registry);
	}

	double ageSeconds() {
		try {
			Instant oldest = jdbc.sql("SELECT created_at FROM outbox WHERE published_at IS NULL ORDER BY id LIMIT 1")
				.query((rs, rowNum) -> rs.getObject("created_at", OffsetDateTime.class).toInstant())
				.optional()
				.orElse(null);
			if (oldest == null) {
				return 0;
			}
			return Math.max(0, Duration.between(oldest, Instant.now()).toMillis() / 1000.0);
		}
		catch (RuntimeException ex) {
			// A database outage must not break metric export; NaN is skipped by the registry.
			log.warn("Could not read the age of the oldest unpublished outbox row: {}", ex.getClass().getSimpleName());
			return Double.NaN;
		}
	}

}
