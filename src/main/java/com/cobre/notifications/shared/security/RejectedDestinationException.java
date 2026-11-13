package com.cobre.notifications.shared.security;

/**
 * A webhook URL that {@link DestinationPolicy} does not allow. The message is a fixed reason for logs and never
 * contains the URL.
 */
public class RejectedDestinationException extends RuntimeException {

	RejectedDestinationException(String reason) {
		super(reason);
	}

}
