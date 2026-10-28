package com.cobre.notifications.notifications.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * What one HTTPS call produced: either an HTTP status or a transport error, never both.
 *
 * @param httpStatus null when no response was received
 * @param error null when a response was received
 */
public record AttemptResult(Instant attemptedAt, Integer httpStatus, TransportError error, int latencyMs) {

	public AttemptResult {
		Objects.requireNonNull(attemptedAt);
		if ((httpStatus == null) == (error == null)) {
			throw new IllegalArgumentException("an attempt has either an http status or a transport error");
		}
		if (latencyMs < 0) {
			throw new IllegalArgumentException("latency must not be negative");
		}
	}

	public static AttemptResult response(Instant attemptedAt, int httpStatus, int latencyMs) {
		return new AttemptResult(attemptedAt, httpStatus, null, latencyMs);
	}

	public static AttemptResult failure(Instant attemptedAt, TransportError error, int latencyMs) {
		return new AttemptResult(attemptedAt, null, error, latencyMs);
	}

	public DeliveryOutcome outcome() {
		return httpStatus != null ? DeliveryOutcome.classify(httpStatus) : DeliveryOutcome.classify(error);
	}

	/**
	 * Null on success; otherwise a short code ({@code http_503}, {@code timeout}) that is safe to store and show.
	 */
	public String errorType() {
		if (error != null) {
			return error.errorType();
		}
		return outcome() == DeliveryOutcome.SUCCESS ? null : "http_" + httpStatus;
	}

}
