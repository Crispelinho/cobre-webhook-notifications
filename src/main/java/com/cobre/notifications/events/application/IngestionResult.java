package com.cobre.notifications.events.application;

import java.util.List;

public record IngestionResult(String eventId, List<String> notificationEventIds) {

	public IngestionResult {
		notificationEventIds = List.copyOf(notificationEventIds);
	}

}
