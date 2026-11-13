package com.cobre.notifications.notifications.adapters.out.http;

import java.time.Instant;

import com.cobre.notifications.notifications.domain.Delivery;
import com.fasterxml.jackson.annotation.JsonProperty;

import tools.jackson.databind.json.JsonMapper;

/**
 * The JSON the receiver gets. {@code content} is sent exactly as stored.
 */
record WebhookBody(@JsonProperty("notification_event_id") String notificationEventId,
		@JsonProperty("event_id") String eventId, @JsonProperty("event_type") String eventType,
		@JsonProperty("content") String content, @JsonProperty("created_at") Instant createdAt) {

	static WebhookBody from(Delivery delivery) {
		return new WebhookBody(delivery.id().value(), delivery.eventId(), delivery.eventType(), delivery.content(),
				delivery.createdAt());
	}

	String toJson(JsonMapper json) {
		return json.writeValueAsString(this);
	}

}
