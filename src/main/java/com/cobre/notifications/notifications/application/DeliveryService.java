package com.cobre.notifications.notifications.application;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

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
import com.cobre.notifications.notifications.domain.Lease;
import com.cobre.notifications.notifications.domain.TransportError;

/**
 * Delivers one notification event per dispatch message. A message for a final notification event or for an earlier
 * round is ignored without sending. Before sending, the worker atomically claims the notification event and gets a
 * lease with a generation token; a worker that cannot claim sends nothing. Every write of the result is conditional on
 * that token, so a stale worker (its lease expired and was taken over) is ignored instead of overwriting the newer
 * result. If the worker stops before recording a result, the lease expires and recovery handles it (see
 * {@link RecoverInterruptedDeliveries}). Logs carry ids and rounds only, never content or destinations.
 */
public class DeliveryService implements DeliverNotification, FailExhaustedNotification {

	private static final Logger log = LoggerFactory.getLogger(DeliveryService.class);

	/** Well above the 10-second total limit of one attempt. */
	public static final Duration DEFAULT_LEASE_DURATION = Duration.ofSeconds(60);

	private final DeliveryStore store;

	private final WebhookSender sender;

	private final Clock clock;

	private final int maxAttempts;

	private final DeliveryMetrics metrics;

	private final Duration leaseDuration;

	private final String workerId = UUID.randomUUID().toString();

	public DeliveryService(DeliveryStore store, WebhookSender sender, Clock clock, int maxAttempts) {
		this(store, sender, clock, maxAttempts, DeliveryMetrics.NONE);
	}

	public DeliveryService(DeliveryStore store, WebhookSender sender, Clock clock, int maxAttempts,
			DeliveryMetrics metrics) {
		this(store, sender, clock, maxAttempts, metrics, DEFAULT_LEASE_DURATION);
	}

	/**
	 * @param leaseDuration how long a claim lasts; must be well above the total time limit of one attempt
	 */
	public DeliveryService(DeliveryStore store, WebhookSender sender, Clock clock, int maxAttempts,
			DeliveryMetrics metrics, Duration leaseDuration) {
		if (maxAttempts < 1) {
			throw new IllegalArgumentException("maxAttempts must be at least 1");
		}
		if (leaseDuration.isZero() || leaseDuration.isNegative()) {
			throw new IllegalArgumentException("leaseDuration must be positive");
		}
		this.leaseDuration = leaseDuration;
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

		Optional<Claimed> claimed = claim(delivery);
		if (claimed.isEmpty()) {
			log.info("Dispatch message for notification event {} round {} ignored: not claimed, another worker holds it",
					message.notificationEventId(), message.round());
			return DeliveryResult.IGNORED;
		}
		// A reclaim may have appended an interrupted attempt, so continue from the state after the claim.
		Lease lease = claimed.get().lease();
		delivery = claimed.get().delivery();
		if (!delivery.hasAttemptsLeft(maxAttempts)) {
			// Only reachable when duplicate messages or interrupted attempts already used the attempts.
			return exhaust(lease, delivery) ? DeliveryResult.FAILED : DeliveryResult.IGNORED;
		}

		// The state is read from the database for every attempt, never from the message. An inactive subscription ends
		// the notification as a permanent failure without a request. A deactivation after this read cannot stop it.
		AttemptResult result = store.isSubscriptionActive(message.notificationEventId()) ? sender.send(delivery)
				: AttemptResult.failure(clock.instant(), TransportError.SUBSCRIPTION_INACTIVE, 0);
		Delivery after = delivery.afterAttempt(result, clock.instant());
		DeliveryAttempt attempt = new DeliveryAttempt(delivery.round(), delivery.nextAttemptNumber(),
				result.attemptedAt(), result.httpStatus(), result.errorType(), result.latencyMs());
		if (!store.recordAttempt(lease, delivery, attempt, after)) {
			log.warn("Attempt {} of notification event {} round {} not recorded: lease generation {} is no longer current",
					attempt.attemptNumber(), message.notificationEventId(), message.round(), lease.generation());
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
		// A lost claim or write means another worker moved the event; look again before giving up.
		for (int tries = 0; tries < 3; tries++) {
			Optional<Delivery> found = store.find(message.notificationEventId());
			if (found.isEmpty() || !found.get().isOpenFor(message.round())) {
				return;
			}
			Optional<Claimed> claimed = claim(found.get());
			if (claimed.isPresent() && exhaust(claimed.get().lease(), claimed.get().delivery())) {
				log.error("Notification event {} round {} failed: retries exhausted", message.notificationEventId(),
						message.round());
				return;
			}
		}
	}

	/**
	 * Claims the notification event and reads it again, because a reclaim of an expired lease changes its state.
	 */
	private Optional<Claimed> claim(Delivery observed) {
		Optional<Lease> lease = store.claim(observed, workerId, clock.instant(), leaseDuration);
		if (lease.isEmpty()) {
			return Optional.empty();
		}
		return store.find(observed.id().value()).map(fresh -> new Claimed(lease.get(), fresh));
	}

	private boolean exhaust(Lease lease, Delivery delivery) {
		Delivery after = delivery.exhausted(clock.instant());
		if (!store.updateState(lease, delivery, after)) {
			return false;
		}
		recordFinal(after);
		return true;
	}

	private record Claimed(Lease lease, Delivery delivery) {
	}

	private void recordFinal(Delivery after) {
		if (after.createdAt() != null && after.deliveryDate() != null) {
			metrics.finalOutcome(after.status(), Duration.between(after.createdAt(), after.deliveryDate()));
		}
	}

}
