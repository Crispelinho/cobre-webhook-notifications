package com.cobre.notifications.notifications.adapters.in.rest;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonProperty;

import com.cobre.notifications.notifications.domain.NotificationEvent;

record NotificationEventResponse(@JsonProperty("notification_event_id") String notificationEventId,
		@JsonProperty("event_id") String eventId, @JsonProperty("event_type") String eventType, String content,
		@JsonProperty("delivery_status") String deliveryStatus, @JsonProperty("created_at") Instant createdAt,
		@JsonProperty("delivery_date") Instant deliveryDate, @JsonProperty("delivered_at") Instant deliveredAt) {

	static NotificationEventResponse from(NotificationEvent event) {
		return new NotificationEventResponse(event.id().value(), event.eventId(), event.eventType(), event.content(),
				event.deliveryStatus().wireValue(), event.createdAt(), event.deliveryDate(), event.deliveredAt());
	}

}
