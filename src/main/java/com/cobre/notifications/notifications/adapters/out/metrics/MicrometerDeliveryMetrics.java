package com.cobre.notifications.notifications.adapters.out.metrics;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import com.cobre.notifications.notifications.application.port.out.DeliveryMetrics;
import com.cobre.notifications.notifications.domain.DeliveryOutcome;
import com.cobre.notifications.notifications.domain.DeliveryStatus;

/**
 * Micrometer implementation of {@link DeliveryMetrics}, exported over OTLP. Every meter is registered at startup so
 * counters exist (at zero) before the first event: a rate or {@code increase()} alert then sees the first increment.
 * Tags are bounded enums only, never ids, so cardinality stays constant.
 */
@Component
class MicrometerDeliveryMetrics implements DeliveryMetrics {

	static final String ATTEMPTS = "notification.delivery.attempts";

	static final String ATTEMPT_DURATION = "notification.delivery.attempt.duration";

	static final String END_TO_END_DURATION = "notification.delivery.end_to_end.duration";

	static final String DEAD_LETTER = "notification.dead_letter";

	private final Map<DeliveryOutcome, Counter> attempts = new EnumMap<>(DeliveryOutcome.class);

	private final Map<DeliveryOutcome, Timer> attemptDurations = new EnumMap<>(DeliveryOutcome.class);

	private final Map<DeliveryStatus, Timer> endToEnd = new EnumMap<>(DeliveryStatus.class);

	private final Counter deadLetter;

	MicrometerDeliveryMetrics(MeterRegistry registry) {
		for (DeliveryOutcome outcome : DeliveryOutcome.values()) {
			String tag = outcome.name().toLowerCase(Locale.ROOT);
			attempts.put(outcome, Counter.builder(ATTEMPTS)
				.description("Webhook delivery attempts by outcome")
				.tag("outcome", tag)
				.register(registry));
			attemptDurations.put(outcome, Timer.builder(ATTEMPT_DURATION)
				.description("Latency of one webhook delivery attempt")
				.tag("outcome", tag)
				.publishPercentileHistogram()
				.maximumExpectedValue(Duration.ofSeconds(30))
				.register(registry));
		}
		for (DeliveryStatus status : new DeliveryStatus[] { DeliveryStatus.COMPLETED, DeliveryStatus.FAILED }) {
			endToEnd.put(status, Timer.builder(END_TO_END_DURATION)
				.description("Time from creation of the notification event to its final outcome")
				.tag("status", status.wireValue())
				.publishPercentileHistogram()
				.maximumExpectedValue(Duration.ofHours(24))
				.register(registry));
		}
		this.deadLetter = Counter.builder(DEAD_LETTER)
			.description("Messages that reached the dispatch dead-letter topic")
			.register(registry);
	}

	@Override
	public void attemptRecorded(DeliveryOutcome outcome, long latencyMs) {
		attempts.get(outcome).increment();
		attemptDurations.get(outcome).record(Duration.ofMillis(Math.max(0, latencyMs)));
	}

	@Override
	public void finalOutcome(DeliveryStatus status, Duration sinceCreation) {
		Timer timer = endToEnd.get(status);
		if (timer != null) {
			timer.record(sinceCreation.isNegative() ? Duration.ZERO : sinceCreation);
		}
	}

	@Override
	public void deadLettered() {
		deadLetter.increment();
	}

}
