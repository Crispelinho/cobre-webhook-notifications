package com.cobre.notifications.notifications.application;

import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cobre.notifications.notifications.application.port.out.DispatchPublisher;
import com.cobre.notifications.notifications.application.port.out.OutboxPort;

/**
 * Publishes pending outbox rows in order. On the first publish failure the batch stops and the failed row (and the
 * ones after it) stay unpublished for the next poll, so an unavailable broker causes no loss and no busy loop.
 */
public class RelayOutbox {

	private static final Logger log = LoggerFactory.getLogger(RelayOutbox.class);

	private final OutboxPort outbox;

	private final DispatchPublisher publisher;

	private final int batchSize;

	public RelayOutbox(OutboxPort outbox, DispatchPublisher publisher, int batchSize) {
		if (batchSize < 1) {
			throw new IllegalArgumentException("batchSize must be at least 1");
		}
		this.outbox = Objects.requireNonNull(outbox);
		this.publisher = Objects.requireNonNull(publisher);
		this.batchSize = batchSize;
	}

	/**
	 * @return the number of messages published and marked in this poll
	 */
	public int relayPending() {
		return outbox.relayBatch(batchSize, message -> {
			try {
				publisher.publish(message);
				return true;
			}
			catch (DispatchPublishException ex) {
				// Ids and rounds only; never event content or destinations.
				log.warn("Dispatch publish failed for notification event {} round {}; will retry on next poll",
						message.notificationEventId(), message.round(), ex);
				return false;
			}
		});
	}

}
