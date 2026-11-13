package com.cobre.notifications.subscriptions.domain;

/**
 * A client already has the maximum number of active subscriptions, so a creation or a reactivation is refused. The
 * message is fixed text and never echoes request values.
 */
public class SubscriptionLimitReachedException extends RuntimeException {

	/** Stable machine-readable code returned to clients with the {@code 409} response. */
	public static final String CODE = "SUBSCRIPTION_LIMIT_REACHED";

	public SubscriptionLimitReachedException(int limit) {
		super("the client already has the maximum of " + limit + " active subscriptions");
	}

}
