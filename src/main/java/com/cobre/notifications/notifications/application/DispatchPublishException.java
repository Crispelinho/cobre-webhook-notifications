package com.cobre.notifications.notifications.application;

/**
 * The transport did not confirm a dispatch message. The message is not lost: its outbox row stays unpublished and the
 * relay tries again on the next poll.
 */
public class DispatchPublishException extends RuntimeException {

	public DispatchPublishException(String message, Throwable cause) {
		super(message, cause);
	}

}
