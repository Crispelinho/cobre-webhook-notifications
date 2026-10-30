package com.cobre.notifications.events.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

import com.cobre.notifications.events.application.port.in.IngestEvent;
import com.cobre.notifications.events.application.port.out.ActiveSubscriptionsPort;
import com.cobre.notifications.events.application.port.out.EventStorePort;
import com.cobre.notifications.events.application.port.out.NotificationIdGenerator;
import com.cobre.notifications.events.domain.Event;
import com.cobre.notifications.events.domain.PendingNotification;
import com.cobre.notifications.events.domain.Subscription;
import com.cobre.notifications.events.domain.SubscriptionMatcher;

public class IngestEventUseCase implements IngestEvent {

	private final ActiveSubscriptionsPort subscriptions;

	private final EventStorePort store;

	private final NotificationIdGenerator ids;

	private final Clock clock;

	public IngestEventUseCase(ActiveSubscriptionsPort subscriptions, EventStorePort store, NotificationIdGenerator ids,
			Clock clock) {
		this.subscriptions = Objects.requireNonNull(subscriptions);
		this.store = Objects.requireNonNull(store);
		this.ids = Objects.requireNonNull(ids);
		this.clock = Objects.requireNonNull(clock);
	}

	@Override
	public IngestionResult ingest(String eventId, String clientId, String eventType, String content) {
		Instant now = clock.instant();
		Event event = new Event(eventId, clientId, eventType, content, now);

		List<Subscription> matches = SubscriptionMatcher.match(event,
				subscriptions.findActiveByClientId(event.clientId()));
		List<PendingNotification> notifications = matches.stream()
			.map(subscription -> PendingNotification.firstRound(ids.next(), event, subscription))
			.toList();

		store.store(event, notifications, now);
		return new IngestionResult(event.id(), notifications.stream().map(PendingNotification::id).toList());
	}

}
