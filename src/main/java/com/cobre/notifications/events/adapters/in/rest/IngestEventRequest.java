package com.cobre.notifications.events.adapters.in.rest;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Explicit request contract. Unknown fields are noted so the controller can reject them (mass assignment, OWASP
 * API3); field rules live in the domain.
 */
class IngestEventRequest {

	@JsonProperty("event_id")
	String eventId;

	@JsonProperty("client_id")
	String clientId;

	@JsonProperty("event_type")
	String eventType;

	@JsonProperty("content")
	String content;

	private boolean hasUnknownFields;

	@JsonAnySetter
	void unknownField(String name, Object value) {
		hasUnknownFields = true;
	}

	boolean hasUnknownFields() {
		return hasUnknownFields;
	}

}
