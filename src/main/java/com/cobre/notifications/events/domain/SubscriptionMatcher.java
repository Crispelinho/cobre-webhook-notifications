package com.cobre.notifications.events.domain;

import java.util.List;

/**
 * Decides which subscriptions receive an event: active, owned by the event's client, and including its type. The
 * client check is repeated here on purpose so a faulty lookup can never leak an event to another client.
 */
public final class SubscriptionMatcher {

	private SubscriptionMatcher() {
	}

	public static List<Subscription> match(Event event, List<Subscription> candidates) {
		return candidates.stream()
			.filter(Subscription::active)
			.filter(subscription -> subscription.clientId().equals(event.clientId()))
			.filter(subscription -> subscription.eventTypes().contains(event.eventType()))
			.toList();
	}

}
