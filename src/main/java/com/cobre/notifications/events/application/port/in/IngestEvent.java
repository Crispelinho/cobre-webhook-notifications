package com.cobre.notifications.events.application.port.in;

import com.cobre.notifications.events.application.DuplicateEventException;
import com.cobre.notifications.events.application.IngestionResult;

public interface IngestEvent {

	/**
	 * Stores the event and creates a pending notification event per matching subscription.
	 * @throws com.cobre.notifications.events.domain.EventValidationException if the event violates a domain rule
	 * @throws DuplicateEventException if the {@code eventId} was already accepted
	 */
	IngestionResult ingest(String eventId, String clientId, String eventType, String content);

}
