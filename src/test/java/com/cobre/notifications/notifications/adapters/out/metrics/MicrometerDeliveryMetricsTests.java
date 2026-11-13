package com.cobre.notifications.notifications.adapters.out.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import com.cobre.notifications.notifications.domain.DeliveryOutcome;
import com.cobre.notifications.notifications.domain.DeliveryStatus;

class MicrometerDeliveryMetricsTests {

	private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

	private final MicrometerDeliveryMetrics metrics = new MicrometerDeliveryMetrics(registry);

	@Test
	void countersExistAtZeroBeforeTheFirstEventSoTheFirstIncrementIsVisibleToRateAlerts() {
		for (String outcome : new String[] { "success", "retryable", "permanent" }) {
			assertThat(registry.get(MicrometerDeliveryMetrics.ATTEMPTS).tag("outcome", outcome).counter().count())
				.isZero();
		}
		assertThat(registry.get(MicrometerDeliveryMetrics.DEAD_LETTER).counter().count()).isZero();
	}

	@Test
	void anAttemptIncrementsItsOutcomeCounterAndRecordsItsLatency() {
		metrics.attemptRecorded(DeliveryOutcome.SUCCESS, 250);
		metrics.attemptRecorded(DeliveryOutcome.RETRYABLE, 10_000);
		metrics.attemptRecorded(DeliveryOutcome.RETRYABLE, 30);

		assertThat(registry.get(MicrometerDeliveryMetrics.ATTEMPTS).tag("outcome", "success").counter().count())
			.isEqualTo(1);
		assertThat(registry.get(MicrometerDeliveryMetrics.ATTEMPTS).tag("outcome", "retryable").counter().count())
			.isEqualTo(2);
		assertThat(registry.get(MicrometerDeliveryMetrics.ATTEMPT_DURATION)
			.tag("outcome", "retryable")
			.timer()
			.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS)).isEqualTo(10_030);
	}

	@Test
	void theTimeToTheFinalOutcomeIsRecordedPerFinalStatus() {
		metrics.finalOutcome(DeliveryStatus.COMPLETED, Duration.ofSeconds(3));
		metrics.finalOutcome(DeliveryStatus.FAILED, Duration.ofMinutes(5));
		metrics.finalOutcome(DeliveryStatus.RETRYING, Duration.ofSeconds(1));

		assertThat(registry.get(MicrometerDeliveryMetrics.END_TO_END_DURATION)
			.tag("status", "completed")
			.timer()
			.count()).isEqualTo(1);
		assertThat(registry.get(MicrometerDeliveryMetrics.END_TO_END_DURATION)
			.tag("status", "failed")
			.timer()
			.totalTime(java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(300);
	}

	@Test
	void aDeadLetterMessageIncrementsTheDeadLetterCounter() {
		metrics.deadLettered();
		metrics.deadLettered();

		assertThat(registry.get(MicrometerDeliveryMetrics.DEAD_LETTER).counter().count()).isEqualTo(2);
	}

}
