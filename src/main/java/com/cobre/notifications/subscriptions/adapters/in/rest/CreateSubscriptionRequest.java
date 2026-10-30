package com.cobre.notifications.subscriptions.adapters.in.rest;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Body of {@code POST /subscriptions}. Only {@code url} and {@code event_types} are accepted: the owner always comes
 * from the access token (OWASP API1), and any other field, such as {@code client_id} or {@code active}, is rejected
 * rather than silently ignored (OWASP API3, mass assignment).
 */
record CreateSubscriptionRequest(String url, @JsonProperty("event_types") List<String> eventTypes) {

	@JsonAnySetter
	void rejectUnknownField(String name, Object value) {
		throw new UnknownFieldException(name);
	}

}
