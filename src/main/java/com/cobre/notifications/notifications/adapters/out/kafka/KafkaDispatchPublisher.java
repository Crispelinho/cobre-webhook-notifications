package com.cobre.notifications.notifications.adapters.out.kafka;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import com.cobre.notifications.notifications.application.DispatchMessage;
import com.cobre.notifications.notifications.application.DispatchPublishException;
import com.cobre.notifications.notifications.application.port.out.DispatchPublisher;

/**
 * Publishes to {@value #TOPIC} keyed by notification event id and waits for the broker acknowledgement. The producer
 * is idempotent with {@code acks=all}. If the wait times out, the record may still reach the broker later, so a
 * message can be delivered twice; the consumer ignores duplicates.
 */
@Component
class KafkaDispatchPublisher implements DispatchPublisher {

	static final String TOPIC = "notification.dispatch";

	private final KafkaTemplate<String, String> kafka;

	private final Duration timeout;

	KafkaDispatchPublisher(KafkaTemplate<String, String> kafka,
			@Value("${app.outbox.relay.publish-timeout:PT5S}") Duration timeout) {
		this.kafka = kafka;
		this.timeout = timeout;
	}

	@Override
	public void publish(DispatchMessage message) {
		// Both fields are a generated UUID and an integer, so the payload needs no JSON escaping.
		String payload = "{\"notification_event_id\":\"%s\",\"round\":%d}".formatted(message.notificationEventId(),
				message.round());
		try {
			kafka.send(TOPIC, message.notificationEventId(), payload).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new DispatchPublishException("Interrupted while publishing dispatch message", ex);
		}
		catch (ExecutionException | TimeoutException | RuntimeException ex) {
			throw new DispatchPublishException("Broker did not confirm dispatch message", ex);
		}
	}

}
