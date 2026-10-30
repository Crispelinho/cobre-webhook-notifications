package com.cobre.notifications.kernel.domain;

import java.util.Objects;

/**
 * Identity of the client that owns a resource: subscriptions and notification events. Shared by both capabilities so
 * the two cannot drift apart. Always taken from the access token, never from a request body.
 */
public record ClientId(String value) {

	public ClientId {
		Objects.requireNonNull(value, "client id is required");
		if (value.isBlank()) {
			throw new IllegalArgumentException("client id must not be blank");
		}
	}

}
