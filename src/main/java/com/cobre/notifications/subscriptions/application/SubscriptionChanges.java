package com.cobre.notifications.subscriptions.application;

import com.cobre.notifications.subscriptions.domain.EventTypes;
import com.cobre.notifications.subscriptions.domain.InvalidSubscriptionException;

/**
 * A partial update. A null field is left unchanged; at least one field is required.
 */
public record SubscriptionChanges(Boolean active, EventTypes eventTypes) {

	public SubscriptionChanges {
		if (active == null && eventTypes == null) {
			throw new InvalidSubscriptionException("at least one of active or event_types is required");
		}
	}

	public static SubscriptionChanges deactivation() {
		return new SubscriptionChanges(false, null);
	}

}
