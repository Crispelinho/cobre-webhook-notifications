package com.cobre.notifications.events.domain;

import java.util.Set;

/**
 * The part of a subscription that event matching needs.
 */
public record Subscription(String id, String clientId, String url, Set<String> eventTypes, boolean active) {

	public Subscription {
		eventTypes = Set.copyOf(eventTypes);
	}

}
