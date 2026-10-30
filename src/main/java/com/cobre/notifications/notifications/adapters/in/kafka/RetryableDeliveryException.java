package com.cobre.notifications.notifications.adapters.in.kafka;

/**
 * Signals Kafka that the attempt failed in a retryable way: the message moves to the next retry topic. The last
 * attempt of a round does not use this exception (see {@link ExhaustedDeliveryException}). Carries no content.
 */
class RetryableDeliveryException extends RuntimeException {

	RetryableDeliveryException(String notificationEventId, int round) {
		super("Delivery of notification event %s round %d must be retried".formatted(notificationEventId, round));
	}

}
