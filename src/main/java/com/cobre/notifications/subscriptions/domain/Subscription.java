package com.cobre.notifications.subscriptions.domain;

import com.cobre.notifications.kernel.domain.ClientId;

import java.time.Instant;
import java.util.Objects;

/**
 * A client's registration of a webhook destination. The URL never changes: a new destination is a new subscription,
 * so the history of notification events stays tied to the URL it was sent to (D13).
 */
public record Subscription(SubscriptionId id, ClientId clientId, WebhookUrl url, EventTypes eventTypes,
		boolean active, Instant createdAt) {

	public Subscription {
		Objects.requireNonNull(id);
		Objects.requireNonNull(clientId);
		Objects.requireNonNull(url);
		Objects.requireNonNull(eventTypes);
		Objects.requireNonNull(createdAt);
	}

	public static Subscription create(SubscriptionId id, ClientId clientId, WebhookUrl url, EventTypes eventTypes,
			Instant createdAt) {
		return new Subscription(id, clientId, url, eventTypes, true, createdAt);
	}

}
