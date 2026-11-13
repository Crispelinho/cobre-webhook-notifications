package com.cobre.notifications.events.application;

/**
 * An event with the same {@code event_id} was already accepted.
 */
public class DuplicateEventException extends RuntimeException {

	public DuplicateEventException(String eventId) {
		super("Event " + eventId + " already exists.");
	}

}
