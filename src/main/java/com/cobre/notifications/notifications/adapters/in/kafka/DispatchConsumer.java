package com.cobre.notifications.notifications.adapters.in.kafka;

import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.cobre.notifications.notifications.application.DeliveryResult;
import com.cobre.notifications.notifications.application.DispatchMessage;
import com.cobre.notifications.notifications.application.port.in.DeliverNotification;

/**
 * Consumes {@value #TOPIC} and, through the retry topics configured in {@link DispatchRetryConfiguration}, its retry
 * topics. The adapter only routes: the delivery rules decide. A {@code RETRY} result becomes a
 * {@link RetryableDeliveryException}, so Kafka moves the message to the next retry topic. An {@code EXHAUSTED} result
 * (the last attempt of the round, already failed by the delivery rules) becomes an {@link ExhaustedDeliveryException},
 * so Kafka sends the message straight to the dead-letter topic.
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
		// Every log line written while this message is handled carries its notification event id.
		try (MDC.MDCCloseable ignored = MDC.putCloseable(LogKeys.NOTIFICATION_EVENT_ID, message.notificationEventId())) {
			DeliveryResult result = delivery.deliver(message);
			if (result == DeliveryResult.RETRY) {
				throw new RetryableDeliveryException(message.notificationEventId(), message.round());
			}
			if (result == DeliveryResult.EXHAUSTED) {
				throw new ExhaustedDeliveryException(message.notificationEventId(), message.round());
			}
		}
	}

}
