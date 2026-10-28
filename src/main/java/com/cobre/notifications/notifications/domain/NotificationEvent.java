package com.cobre.notifications.notifications.domain;

import java.time.Instant;
import java.util.List;

/**
 * One delivery of one event to one subscription.
 *
 * @param deliveryDate final outcome of the current round (completed or failed); null while pending
 * @param deliveredAt first successful (2xx) attempt; null if there was none
 */
public record NotificationEvent(NotificationEventId id, String eventId, ClientId clientId, String eventType,
		String content, DeliveryStatus deliveryStatus, Instant createdAt, Instant deliveryDate, Instant deliveredAt,
		List<DeliveryAttempt> attempts) {

	public NotificationEvent {
		attempts = List.copyOf(attempts);
	}

}
