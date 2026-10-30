package com.cobre.notifications.shared.security;

/**
 * A host name that {@link DestinationPolicy} could not resolve: NXDOMAIN, SERVFAIL, a timeout or an empty answer. Unlike
 * {@link RejectedDestinationException}, the cause may be transient, so the delivery path retries it. The message is a
 * fixed reason for logs and never contains the URL.
 */
public class DestinationResolutionException extends RuntimeException {

	DestinationResolutionException(String reason) {
		super(reason);
	}

}
