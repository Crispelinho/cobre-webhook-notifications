package com.cobre.notifications.subscriptions.adapters.in.rest;

import java.util.List;

import com.cobre.notifications.subscriptions.domain.Subscription;

/**
 * Wrapped in {@code data} so pagination fields can be added later without breaking clients.
 */
record SubscriptionListResponse(List<SubscriptionResponse> data) {

	static SubscriptionListResponse from(List<Subscription> subscriptions) {
		return new SubscriptionListResponse(subscriptions.stream().map(SubscriptionResponse::from).toList());
	}

}
