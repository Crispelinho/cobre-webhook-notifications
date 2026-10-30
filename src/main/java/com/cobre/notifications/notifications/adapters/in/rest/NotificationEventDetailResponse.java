package com.cobre.notifications.notifications.adapters.in.rest;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import com.cobre.notifications.notifications.domain.DeliveryAttempt;
import com.cobre.notifications.notifications.domain.NotificationEvent;

record NotificationEventDetailResponse(@JsonProperty("notification_event_id") String notificationEventId,
		@JsonProperty("event_id") String eventId, @JsonProperty("event_type") String eventType, String content,
		@JsonProperty("delivery_status") String deliveryStatus, @JsonProperty("created_at") Instant createdAt,
		@JsonProperty("delivery_date") Instant deliveryDate, @JsonProperty("delivered_at") Instant deliveredAt,
		List<AttemptResponse> attempts) {

	static NotificationEventDetailResponse from(NotificationEvent event) {
		return new NotificationEventDetailResponse(event.id().value(), event.eventId(), event.eventType(),
				event.content(), event.deliveryStatus().wireValue(), event.createdAt(), event.deliveryDate(),
				event.deliveredAt(), event.attempts().stream().map(AttemptResponse::from).toList());
	}

	record AttemptResponse(int round, @JsonProperty("attempt_number") int attemptNumber,
			@JsonProperty("attempted_at") Instant attemptedAt, @JsonProperty("http_status") Integer httpStatus,
			@JsonProperty("error_type") String errorType, @JsonProperty("latency_ms") Integer latencyMs) {

		static AttemptResponse from(DeliveryAttempt attempt) {
			return new AttemptResponse(attempt.round(), attempt.attemptNumber(), attempt.attemptedAt(),
					attempt.httpStatus(), attempt.errorType(), attempt.latencyMs());
		}

	}

}
