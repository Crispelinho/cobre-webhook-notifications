package com.cobre.notifications.notifications.domain;

/**
 * How the delivery rules treat the result of one attempt. The transport (Kafka) only maps these to "retry" or "stop".
 */
public enum DeliveryOutcome {

	/** The receiver answered 2xx. */
	SUCCESS,
	/** Worth trying again: 5xx, 408, 429, timeouts and connection errors. */
	RETRYABLE,
	/** Retrying cannot help: any other response (redirects included), TLS errors, invalid destinations. */
	PERMANENT;

	public static DeliveryOutcome classify(int httpStatus) {
		if (httpStatus >= 200 && httpStatus < 300) {
			return SUCCESS;
		}
		if (httpStatus >= 500 && httpStatus < 600 || httpStatus == 408 || httpStatus == 429) {
			return RETRYABLE;
		}
		// Other 4xx and every 3xx: a redirect is never followed, so it counts as a permanent failure.
		return PERMANENT;
	}

	public static DeliveryOutcome classify(TransportError error) {
		return switch (error) {
			case TIMEOUT, CONNECTION -> RETRYABLE;
			case TLS, INVALID_DESTINATION, SIGNING_ERROR -> PERMANENT;
		};
	}

}
