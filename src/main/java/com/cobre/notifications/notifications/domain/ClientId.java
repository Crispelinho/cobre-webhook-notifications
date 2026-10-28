package com.cobre.notifications.notifications.domain;

import java.util.Objects;

/**
 * Identity of the client that owns a resource. Always taken from the access token, never from a request.
 */
public record ClientId(String value) {

	public ClientId {
		Objects.requireNonNull(value, "client id is required");
		if (value.isBlank()) {
			throw new IllegalArgumentException("client id must not be blank");
		}
	}

}
