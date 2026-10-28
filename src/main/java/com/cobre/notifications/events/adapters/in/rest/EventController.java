package com.cobre.notifications.events.adapters.in.rest;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.cobre.notifications.events.application.port.in.IngestEvent;

/**
 * Ingestion endpoint for internal producers (scope {@code events:write}). The {@code client_id} in the body is the
 * client the event is about, not the caller: authorization rests on the producer-only scope, so a client token can
 * never reach this code.
 */
@RestController
@RequestMapping("/events")
class EventController {

	private final IngestEvent ingestEvent;

	EventController(IngestEvent ingestEvent) {
		this.ingestEvent = ingestEvent;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.ACCEPTED)
	IngestEventResponse ingest(@RequestBody IngestEventRequest request) {
		if (request.hasUnknownFields()) {
			throw new IllegalArgumentException("Request contains unknown fields.");
		}
		return IngestEventResponse
			.from(ingestEvent.ingest(request.eventId, request.clientId, request.eventType, request.content));
	}

}
