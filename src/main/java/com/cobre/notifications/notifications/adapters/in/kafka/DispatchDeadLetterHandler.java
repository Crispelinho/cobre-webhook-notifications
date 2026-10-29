package com.cobre.notifications.notifications.adapters.in.kafka;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.cobre.notifications.notifications.application.DispatchMessage;
import com.cobre.notifications.notifications.application.port.in.FailExhaustedNotification;
import com.cobre.notifications.notifications.application.port.out.DeliveryMetrics;

/**
 * Receives the messages that exhausted every attempt and marks their notification events {@code failed}. A replay
 * starts from the database, never from this topic.
 */
@Component
@ConditionalOnProperty(name = "app.delivery.consumer.enabled", havingValue = "true", matchIfMissing = true)
class DispatchDeadLetterHandler {

	private static final Logger log = LoggerFactory.getLogger(DispatchDeadLetterHandler.class);

	private final FailExhaustedNotification failExhausted;

	private final DispatchPayloadParser parser;

	private final DeliveryMetrics metrics;

	DispatchDeadLetterHandler(FailExhaustedNotification failExhausted, DispatchPayloadParser parser,
			DeliveryMetrics metrics) {
		this.failExhausted = failExhausted;
		this.parser = parser;
		this.metrics = metrics;
	}

	void onDeadLetter(String payload) {
		// Counted first: the alert is about messages reaching the topic, whatever happens to them next.
		metrics.deadLettered();
		try {
			DispatchMessage message = parser.parse(payload);
			try (MDC.MDCCloseable ignored = MDC.putCloseable(LogKeys.NOTIFICATION_EVENT_ID,
					message.notificationEventId())) {
				failExhausted.failExhausted(message);
			}
		}
		catch (MalformedDispatchMessageException ex) {
			// Nothing to mark: there is no notification event to point at.
			log.error("Unreadable message reached the dispatch dead-letter topic; ignored", ex);
		}
	}

}
