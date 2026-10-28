package com.cobre.notifications.subscriptions.domain;

/**
 * A subscription request that breaks a domain rule. The message is fixed text and never echoes request values.
 */
public class InvalidSubscriptionException extends RuntimeException {

	public InvalidSubscriptionException(String message) {
		super(message);
	}

}
