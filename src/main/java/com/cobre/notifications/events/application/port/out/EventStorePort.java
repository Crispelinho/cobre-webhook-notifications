package com.cobre.notifications.events.application.port.out;

import java.time.Instant;
import java.util.List;

import com.cobre.notifications.events.application.DuplicateEventException;
import com.cobre.notifications.events.domain.Event;
import com.cobre.notifications.events.domain.PendingNotification;

public interface EventStorePort {

	/**
	 * Atomically stores the event, its notification events and one outbox row per notification event: all or nothing.
	 * @throws DuplicateEventException if an event with the same id exists; nothing is stored
	 */
	void store(Event event, List<PendingNotification> notifications, Instant outboxCreatedAt);

}
