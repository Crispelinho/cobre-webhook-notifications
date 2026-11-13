package com.cobre.notifications.events.domain;

/**
 * A notification event created by ingestion: {@code pending}, first round, with the subscription URL as a snapshot.
 */
public record PendingNotification(String id, String eventId, String subscriptionId, String clientId,
		String destinationUrl, int round) {

	public static final int FIRST_ROUND = 1;

	public static PendingNotification firstRound(String id, Event event, Subscription subscription) {
		return new PendingNotification(id, event.id(), subscription.id(), event.clientId(), subscription.url(),
				FIRST_ROUND);
	}

}
