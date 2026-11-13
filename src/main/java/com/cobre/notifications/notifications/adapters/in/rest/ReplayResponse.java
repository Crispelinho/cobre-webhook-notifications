package com.cobre.notifications.notifications.adapters.in.rest;

import com.fasterxml.jackson.annotation.JsonProperty;

import com.cobre.notifications.notifications.domain.NotificationEvent;

record ReplayResponse(@JsonProperty("notification_event_id") String notificationEventId, int round,
		@JsonProperty("delivery_status") String deliveryStatus) {

	static ReplayResponse from(NotificationEvent event) {
		return new ReplayResponse(event.id().value(), event.round(), event.deliveryStatus().wireValue());
	}

}
