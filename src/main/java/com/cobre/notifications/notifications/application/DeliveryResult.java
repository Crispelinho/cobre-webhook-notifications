package com.cobre.notifications.notifications.application;

/**
 * What the transport must do after a dispatch message was handled.
 */
public enum DeliveryResult {

	/** The receiver accepted the webhook. */
	DELIVERED,
	/** The attempt failed in a way worth repeating: the transport schedules the next attempt. */
	RETRY,
	/** The receiver asked to wait (Retry-After): the next attempt is stored and the scheduler enqueues it. */
	SCHEDULED,
	/** The notification event is {@code failed}; do not retry. */
	FAILED,
	/**
	 * The attempt just recorded was the last one of the round and failed in a retryable way: the notification event is
	 * already {@code failed}. The message must go straight to the dead-letter topic, with no further retry hop.
	 */
	EXHAUSTED,
	/** Nothing to do: the message is a duplicate, stale, or refers to an unknown notification event. */
	IGNORED

}
