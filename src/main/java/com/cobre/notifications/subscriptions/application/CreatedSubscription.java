package com.cobre.notifications.subscriptions.application;

import com.cobre.notifications.subscriptions.domain.Subscription;

/**
 * Result of a creation: the only moment the plain signing secret leaves the service.
 */
public record CreatedSubscription(Subscription subscription, String secret) {

	@Override
	public String toString() {
		return "CreatedSubscription[subscription=" + subscription + ", secret=***]";
	}

}
