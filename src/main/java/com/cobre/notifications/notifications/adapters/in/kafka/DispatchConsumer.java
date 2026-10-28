package com.cobre.notifications.notifications.adapters.in.kafka;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.cobre.notifications.notifications.application.DeliveryResult;
import com.cobre.notifications.notifications.application.DispatchMessage;
import com.cobre.notifications.notifications.application.port.in.DeliverNotification;

/**
 * Consumes {@value #TOPIC} and, through the retry topics configured in {@link DispatchRetryConfiguration}, its retry
 * topics. The adapter only routes: the delivery rules decide, and a {@code RETRY} result becomes an exception so
 * Kafka moves the message to the next retry topic (or to the dead-letter topic after the last attempt).
 */
@Component
@ConditionalOnProperty(name = "app.delivery.consumer.enabled", havingValue = "true", matchIfMissing = true)
class DispatchConsumer {

	static final String TOPIC = "notification.dispatch";

	private final DeliverNotification delivery;

	private final DispatchPayloadParser parser;

	DispatchConsumer(DeliverNotification delivery, DispatchPayloadParser parser) {
		this.delivery = delivery;
		this.parser = parser;
	}

	@KafkaListener(id = "dispatch-consumer", topics = TOPIC,
			groupId = "${spring.kafka.consumer.group-id:notification-service}")
	void onMessage(String payload) {
		DispatchMessage message = parser.parse(payload);
		if (delivery.deliver(message) == DeliveryResult.RETRY) {
			throw new RetryableDeliveryException(message.notificationEventId(), message.round());
		}
	}

}
