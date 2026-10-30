package com.cobre.notifications.notifications.domain;

import java.time.Instant;

/**
 * One HTTPS call for a notification event. {@code httpStatus} is null when no response was received.
 *
 * <p>
 * {@link #INTERRUPTED} marks an attempt whose worker stopped before recording a result. Its outcome is unknown: the
 * request may have reached the receiver, so it is never treated as a receiver failure. The next attempt sends again
 * with the same {@code Cobre-Notification-Id} and the receiver deduplicates (at-least-once delivery).
 */
public record DeliveryAttempt(int round, int attemptNumber, Instant attemptedAt, Integer httpStatus, String errorType,
		Integer latencyMs) {

	/** {@code error_type} of an attempt that was interrupted before its result was recorded. */
	public static final String INTERRUPTED = "interrupted";

}
