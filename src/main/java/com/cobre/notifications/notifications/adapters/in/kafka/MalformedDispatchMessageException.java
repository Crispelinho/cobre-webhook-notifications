package com.cobre.notifications.notifications.adapters.in.kafka;

/**
 * A dispatch payload that cannot be read. Retrying cannot fix it, so it goes straight to the dead-letter topic.
 */
class MalformedDispatchMessageException extends RuntimeException {

	MalformedDispatchMessageException(String message, Throwable cause) {
		super(message, cause);
	}

}
