package com.cobre.notifications.notifications.domain;

import java.time.Instant;

/**
 * One HTTPS call for a notification event. {@code httpStatus} is null when no response was received.
 */
public record DeliveryAttempt(int round, int attemptNumber, Instant attemptedAt, Integer httpStatus, String errorType,
		Integer latencyMs) {
}
