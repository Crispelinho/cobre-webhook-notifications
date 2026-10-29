package com.cobre.notifications.notifications.domain;

import com.cobre.notifications.kernel.domain.ClientId;

import java.time.Instant;
import java.util.List;

/**
 * One delivery of one event to one subscription.
 *
 * @param deliveryStatus status of the current round
 * @param deliveryDate final outcome of the current round (completed or failed); null while pending
 * @param deliveredAt first successful (2xx) attempt; null if there was none
 * @param lastError code of the last failed attempt of the current round; null when there is none
 * @param round current delivery round; starts at 1 and grows by one with every replay
 * @param version optimistic lock: a state change applies only if the stored version is still this one
 */
public record NotificationEvent(NotificationEventId id, String eventId, ClientId clientId, String eventType,
		String content, DeliveryStatus deliveryStatus, Instant createdAt, Instant deliveryDate, Instant deliveredAt,
		String lastError, int round, long version, List<DeliveryAttempt> attempts) {

	public NotificationEvent {
		attempts = List.copyOf(attempts);
	}

	/**
	 * Starts a new round: the event becomes pending again and has no final outcome yet. The ID, the history and
	 * {@code deliveredAt} stay as they are; {@code lastError} is cleared because the new round has no error yet. The
	 * version is not changed here; the store increments it when it writes the change.
	 *
	 * @throws NotReplayableException if the event is not {@code failed}
	 */
	public NotificationEvent replay() {
		if (deliveryStatus != DeliveryStatus.FAILED) {
			throw new NotReplayableException();
		}
		return new NotificationEvent(id, eventId, clientId, eventType, content, DeliveryStatus.PENDING, createdAt, null,
				deliveredAt, null, round + 1, version, attempts);
	}

}
