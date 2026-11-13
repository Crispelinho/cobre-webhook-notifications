package com.cobre.notifications.subscriptions.adapters.in.rest;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Body of {@code PATCH /subscriptions/{id}}: only {@code active} and {@code event_types}. Any other field, including
 * {@code url}, is rejected; a new destination requires deleting and creating a subscription.
 */
record UpdateSubscriptionRequest(Boolean active, @JsonProperty("event_types") List<String> eventTypes) {

	@JsonAnySetter
	void rejectUnknownField(String name, Object value) {
		throw new UnknownFieldException(name);
	}

}
