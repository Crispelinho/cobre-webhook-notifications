package com.cobre.notifications.notifications.domain;

/**
 * A replay was refused because the subscription is inactive. It is a {@link NotReplayableException}, so it maps to
 * {@code 409 Conflict}; the message is fixed text.
 */
public class SubscriptionInactiveException extends NotReplayableException {

	public static final String MESSAGE = "The subscription is inactive; activate it before replaying.";

	public SubscriptionInactiveException() {
		super(MESSAGE);
	}

}
