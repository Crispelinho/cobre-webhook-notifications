package com.cobre.notifications.notifications.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * The delivery side of a notification event: everything needed to send it and the state machine that applies the
 * result of each attempt.
 *
 * <pre>
 * pending  --SUCCESS--&gt; completed
 * pending  --PERMANENT--&gt; failed
 * pending  --RETRYABLE--&gt; retrying
 * retrying --SUCCESS / PERMANENT / RETRYABLE--&gt; completed / failed / retrying
 * pending | retrying --retries exhausted--&gt; failed
 * </pre>
 *
 * {@code completed} and {@code failed} are final for the round; only a replay (a new round) leaves them.
 *
 * @param attemptsInRound attempts already recorded for {@code round}
 * @param deliveryDate final outcome of the round; null while pending or retrying
 * @param deliveredAt first successful (2xx) attempt; null if there was none
 * @param lastError code of the last failed attempt; never content or secrets
 * @param version optimistic lock: a state change applies only if the stored version is still this one
 * @param subscriptionId owner of {@code secretCiphertext}; the associated data needed to decrypt it
 * @param secretCiphertext the subscription secret exactly as stored (encrypted), or null for an unsigned
 * subscription. The domain never sees the plaintext: the sender decrypts it for one request and drops it.
 * @param nextAttemptAt when set, the receiver asked (Retry-After) to wait: the scheduler enqueues the next attempt
 * then, and an earlier dispatch message sends nothing. Null in every other case.
 */
public record Delivery(NotificationEventId id, String eventId, String eventType, String content,
		String destinationUrl, Instant createdAt, DeliveryStatus status, int round, int attemptsInRound,
		Instant deliveryDate, Instant deliveredAt, String lastError, long version, String subscriptionId,
		byte[] secretCiphertext, Instant nextAttemptAt) {

	public Delivery(NotificationEventId id, String eventId, String eventType, String content, String destinationUrl,
			Instant createdAt, DeliveryStatus status, int round, int attemptsInRound, Instant deliveryDate,
			Instant deliveredAt, String lastError, long version, String subscriptionId, byte[] secretCiphertext) {
		this(id, eventId, eventType, content, destinationUrl, createdAt, status, round, attemptsInRound, deliveryDate,
				deliveredAt, lastError, version, subscriptionId, secretCiphertext, null);
	}

	/**
	 * A delivery without signing material (unsigned subscription).
	 */
	public Delivery(NotificationEventId id, String eventId, String eventType, String content, String destinationUrl,
			Instant createdAt, DeliveryStatus status, int round, int attemptsInRound, Instant deliveryDate,
			Instant deliveredAt, String lastError, long version) {
		this(id, eventId, eventType, content, destinationUrl, createdAt, status, round, attemptsInRound,
				deliveryDate, deliveredAt, lastError, version, null, null);
	}

	public Delivery {
		Objects.requireNonNull(id);
		Objects.requireNonNull(status);
		if (round < 1) {
			throw new IllegalArgumentException("round must be at least 1");
		}
		if (attemptsInRound < 0) {
			throw new IllegalArgumentException("attemptsInRound must not be negative");
		}
	}

	/**
	 * Whether a dispatch message of {@code messageRound} still has work to do: the event is not final and the message
	 * belongs to the current round.
	 */
	public boolean isOpenFor(int messageRound) {
		return round == messageRound && (status == DeliveryStatus.PENDING || status == DeliveryStatus.RETRYING);
	}

	public boolean hasAttemptsLeft(int maxAttempts) {
		return attemptsInRound < maxAttempts;
	}

	public int nextAttemptNumber() {
		return attemptsInRound + 1;
	}

	/**
	 * The state after one attempt. Retries are not capped here: exhaustion is decided by the transport, which then
	 * calls {@link #exhausted(Instant)}.
	 */
	public Delivery afterAttempt(AttemptResult result, Instant now) {
		requireOpen();
		return switch (result.outcome()) {
			case SUCCESS -> new Delivery(id, eventId, eventType, content, destinationUrl, createdAt,
					DeliveryStatus.COMPLETED, round, attemptsInRound + 1, now,
					deliveredAt != null ? deliveredAt : result.attemptedAt(), null, version, subscriptionId, secretCiphertext);
			case RETRYABLE -> new Delivery(id, eventId, eventType, content, destinationUrl, createdAt,
					DeliveryStatus.RETRYING, round, attemptsInRound + 1, null, deliveredAt, result.errorType(),
					version, subscriptionId, secretCiphertext);
			case PERMANENT -> new Delivery(id, eventId, eventType, content, destinationUrl, createdAt,
					DeliveryStatus.FAILED, round, attemptsInRound + 1, now, deliveredAt, result.errorType(), version,
					subscriptionId, secretCiphertext);
		};
	}

	/**
	 * The state once no attempt is left. Keeps the error of the last attempt.
	 */
	public Delivery exhausted(Instant now) {
		requireOpen();
		return new Delivery(id, eventId, eventType, content, destinationUrl, createdAt, DeliveryStatus.FAILED, round,
				attemptsInRound, now, deliveredAt, lastError != null ? lastError : "retries_exhausted", version, subscriptionId,
				secretCiphertext);
	}

	private void requireOpen() {
		if (!isOpenFor(round)) {
			throw new IllegalStateException("notification event %s is %s and takes no more attempts"
				.formatted(id.value(), status.wireValue()));
		}
	}

}
