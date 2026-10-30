package com.cobre.notifications.subscriptions.adapters.in.rest;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import com.cobre.notifications.subscriptions.domain.Subscription;

/**
 * A subscription as read endpoints return it. It has no secret field, so a secret cannot leak by accident.
 */
record SubscriptionResponse(@JsonProperty("subscription_id") String subscriptionId, String url,
		@JsonProperty("event_types") List<String> eventTypes, boolean active,
		@JsonProperty("created_at") Instant createdAt) {

	static SubscriptionResponse from(Subscription subscription) {
		return new SubscriptionResponse(subscription.id().value(), subscription.url().value(),
				subscription.eventTypes().values(), subscription.active(), subscription.createdAt());
	}

}
