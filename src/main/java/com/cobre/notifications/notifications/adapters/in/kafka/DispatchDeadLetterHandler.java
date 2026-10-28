package com.cobre.notifications.notifications.adapters.in.kafka;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.cobre.notifications.notifications.application.port.in.FailExhaustedNotification;

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

	DispatchDeadLetterHandler(FailExhaustedNotification failExhausted, DispatchPayloadParser parser) {
		this.failExhausted = failExhausted;
		this.parser = parser;
	}

	void onDeadLetter(String payload) {
		try {
			failExhausted.failExhausted(parser.parse(payload));
		}
		catch (MalformedDispatchMessageException ex) {
			// Nothing to mark: there is no notification event to point at.
			log.error("Unreadable message reached the dispatch dead-letter topic; ignored", ex);
		}
	}

}
