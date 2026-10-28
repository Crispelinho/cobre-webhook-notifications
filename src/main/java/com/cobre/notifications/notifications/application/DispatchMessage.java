package com.cobre.notifications.notifications.application;

import java.util.Objects;

/**
 * The only content of a dispatch message: which notification event to deliver and in which round. The consumer loads
 * everything else from the database.
 */
public record DispatchMessage(String notificationEventId, int round) {

	public DispatchMessage {
		Objects.requireNonNull(notificationEventId);
		if (round < 1) {
			throw new IllegalArgumentException("round must be at least 1");
		}
	}

}
