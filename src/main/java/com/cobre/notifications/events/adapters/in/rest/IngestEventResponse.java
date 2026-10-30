package com.cobre.notifications.events.adapters.in.rest;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import com.cobre.notifications.events.application.IngestionResult;

record IngestEventResponse(@JsonProperty("event_id") String eventId,
		@JsonProperty("notification_event_ids") List<String> notificationEventIds) {

	static IngestEventResponse from(IngestionResult result) {
		return new IngestEventResponse(result.eventId(), result.notificationEventIds());
	}

}
