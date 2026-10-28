package com.cobre.notifications.notifications.domain;

import java.util.Objects;

public record NotificationEventId(String value) {

	public NotificationEventId {
		Objects.requireNonNull(value, "notification event id is required");
		if (value.isBlank()) {
			throw new IllegalArgumentException("notification event id must not be blank");
		}
	}

}
