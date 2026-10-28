package com.cobre.notifications.notifications.application;

/**
 * What the transport must do after a dispatch message was handled.
 */
public enum DeliveryResult {

	/** The receiver accepted the webhook. */
	DELIVERED,
	/** The attempt failed in a way worth repeating: schedule the next attempt. */
	RETRY,
	/** The notification event is {@code failed}; do not retry. */
	FAILED,
	/** Nothing to do: the message is a duplicate, stale, or refers to an unknown notification event. */
	IGNORED

}
