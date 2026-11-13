package com.cobre.notifications.subscriptions.adapters.in.rest;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import com.cobre.notifications.subscriptions.application.CreatedSubscription;
import com.cobre.notifications.subscriptions.domain.Subscription;

/**
 * Response of {@code POST /subscriptions}: the only response that carries the signing secret.
 */
record CreatedSubscriptionResponse(@JsonProperty("subscription_id") String subscriptionId, String url,
		@JsonProperty("event_types") List<String> eventTypes, boolean active,
		@JsonProperty("created_at") Instant createdAt, String secret) {

	static CreatedSubscriptionResponse from(CreatedSubscription created) {
		Subscription subscription = created.subscription();
		return new CreatedSubscriptionResponse(subscription.id().value(), subscription.url().value(),
				subscription.eventTypes().values(), subscription.active(), subscription.createdAt(), created.secret());
	}

	@Override
	public String toString() {
		return "CreatedSubscriptionResponse[subscriptionId=" + subscriptionId + ", secret=***]";
	}

}
