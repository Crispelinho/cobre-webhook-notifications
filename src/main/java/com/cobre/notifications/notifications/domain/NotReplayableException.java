package com.cobre.notifications.notifications.domain;

/**
 * A replay was refused because the notification event is not {@code failed}: it is still in progress, already
 * completed, or another replay already started the next round. The message is fixed on purpose: it ends up in the
 * {@code 409} response and must not reflect request input or reveal the current status.
 */
public class NotReplayableException extends RuntimeException {

	public static final String MESSAGE = "Only a failed notification event can be replayed.";

	public NotReplayableException() {
		super(MESSAGE);
	}

}
