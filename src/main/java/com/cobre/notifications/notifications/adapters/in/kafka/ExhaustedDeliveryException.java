package com.cobre.notifications.notifications.adapters.in.kafka;

/**
 * Signals Kafka that the last attempt of the round failed and the notification event is already {@code failed}: the
 * message goes straight to the dead-letter topic, with no further retry hop. Carries no content.
 */
class ExhaustedDeliveryException extends RuntimeException {

	ExhaustedDeliveryException(String notificationEventId, int round) {
		super("Delivery of notification event %s round %d used its last attempt".formatted(notificationEventId, round));
	}

}
