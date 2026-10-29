package com.cobre.notifications.notifications.application;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cobre.notifications.notifications.application.port.in.DeliverNotification;
import com.cobre.notifications.notifications.application.port.in.FailExhaustedNotification;
import com.cobre.notifications.notifications.application.port.out.DeliveryMetrics;
import com.cobre.notifications.notifications.application.port.out.DeliveryStore;
import com.cobre.notifications.notifications.application.port.out.WebhookSender;
import com.cobre.notifications.notifications.domain.AttemptResult;
import com.cobre.notifications.notifications.domain.Delivery;
import com.cobre.notifications.notifications.domain.DeliveryAttempt;
import com.cobre.notifications.notifications.domain.DeliveryStatus;

/**
 * Delivers one notification event per dispatch message. A message for a final notification event or for an earlier
 * round is ignored without sending; the version-conditional write makes a concurrent duplicate lose instead of
 * recording a second attempt. Logs carry ids and rounds only, never content or destinations.
 */
public class DeliveryService implements DeliverNotification, FailExhaustedNotification {

	private static final Logger log = LoggerFactory.getLogger(DeliveryService.class);

	private final DeliveryStore store;

	private final WebhookSender sender;

	private final Clock clock;

	private final int maxAttempts;

	private final DeliveryMetrics metrics;

	public DeliveryService(DeliveryStore store, WebhookSender sender, Clock clock, int maxAttempts) {
		this(store, sender, clock, maxAttempts, DeliveryMetrics.NONE);
	}

	public DeliveryService(DeliveryStore store, WebhookSender sender, Clock clock, int maxAttempts,
			DeliveryMetrics metrics) {
		if (maxAttempts < 1) {
			throw new IllegalArgumentException("maxAttempts must be at least 1");
		}
		this.store = Objects.requireNonNull(store);
		this.sender = Objects.requireNonNull(sender);
		this.clock = Objects.requireNonNull(clock);
		this.maxAttempts = maxAttempts;
		this.metrics = Objects.requireNonNull(metrics);
	}

	@Override
	public DeliveryResult deliver(DispatchMessage message) {
		Optional<Delivery> found = store.find(message.notificationEventId());
		if (found.isEmpty()) {
			log.warn("Dispatch message for unknown notification event {} ignored", message.notificationEventId());
			return DeliveryResult.IGNORED;
		}
		Delivery delivery = found.get();
		if (!delivery.isOpenFor(message.round())) {
			log.info("Dispatch message for notification event {} round {} ignored: event is {} in round {}",
					message.notificationEventId(), message.round(), delivery.status().wireValue(), delivery.round());
			return DeliveryResult.IGNORED;
		}
		if (!delivery.hasAttemptsLeft(maxAttempts)) {
			// Only reachable when duplicate messages already used the attempts; do not send a sixth request.
			return exhaust(delivery) ? DeliveryResult.FAILED : DeliveryResult.IGNORED;
		}

		AttemptResult result = sender.send(delivery);
		Delivery after = delivery.afterAttempt(result, clock.instant());
		DeliveryAttempt attempt = new DeliveryAttempt(delivery.round(), delivery.nextAttemptNumber(),
				result.attemptedAt(), result.httpStatus(), result.errorType(), result.latencyMs());
		if (!store.recordAttempt(delivery, attempt, after)) {
			log.warn("Attempt {} of notification event {} round {} not recorded: another consumer changed it first",
					attempt.attemptNumber(), message.notificationEventId(), message.round());
			return DeliveryResult.IGNORED;
		}
		metrics.attemptRecorded(result.outcome(), result.latencyMs());
		log.info("Attempt {} of notification event {} round {} recorded: {}", attempt.attemptNumber(),
				message.notificationEventId(), message.round(), result.outcome());
		if (after.status() == DeliveryStatus.COMPLETED || after.status() == DeliveryStatus.FAILED) {
			recordFinal(after);
		}
		return switch (result.outcome()) {
			case SUCCESS -> DeliveryResult.DELIVERED;
			case RETRYABLE -> DeliveryResult.RETRY;
			case PERMANENT -> DeliveryResult.FAILED;
		};
	}

	@Override
	public void failExhausted(DispatchMessage message) {
		// A lost version race means another consumer moved the event; look again before giving up.
		for (int tries = 0; tries < 3; tries++) {
			Optional<Delivery> found = store.find(message.notificationEventId());
			if (found.isEmpty() || !found.get().isOpenFor(message.round())) {
				return;
			}
			if (exhaust(found.get())) {
				log.error("Notification event {} round {} failed: retries exhausted", message.notificationEventId(),
						message.round());
				return;
			}
		}
	}

	private boolean exhaust(Delivery delivery) {
		Delivery after = delivery.exhausted(clock.instant());
		if (!store.updateState(delivery, after)) {
			return false;
		}
		recordFinal(after);
		return true;
	}

	private void recordFinal(Delivery after) {
		if (after.createdAt() != null && after.deliveryDate() != null) {
			metrics.finalOutcome(after.status(), Duration.between(after.createdAt(), after.deliveryDate()));
		}
	}

}
