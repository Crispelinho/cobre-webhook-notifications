package com.cobre.notifications.notifications.adapters.in.kafka;

/**
 * Signals Kafka that the attempt failed in a retryable way: the message moves to the next retry topic, or to the
 * dead-letter topic after the last attempt. Carries no content.
 */
class RetryableDeliveryException extends RuntimeException {

	RetryableDeliveryException(String notificationEventId, int round) {
		super("Delivery of notification event %s round %d must be retried".formatted(notificationEventId, round));
	}

}
