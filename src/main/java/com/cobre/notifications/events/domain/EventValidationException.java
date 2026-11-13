package com.cobre.notifications.events.domain;

/**
 * An event that breaks a domain rule. The message is fixed text and never echoes request values, so it is safe to
 * return to the client.
 */
public class EventValidationException extends RuntimeException {

	public EventValidationException(String message) {
		super(message);
	}

}
