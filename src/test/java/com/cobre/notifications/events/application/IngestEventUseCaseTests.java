package com.cobre.notifications.events.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.cobre.notifications.events.application.port.out.ActiveSubscriptionsPort;
import com.cobre.notifications.events.application.port.out.EventStorePort;
import com.cobre.notifications.events.application.port.out.NotificationIdGenerator;
import com.cobre.notifications.events.domain.Event;
import com.cobre.notifications.events.domain.EventValidationException;
import com.cobre.notifications.events.domain.PendingNotification;
import com.cobre.notifications.events.domain.Subscription;

class IngestEventUseCaseTests {

	private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

	private final List<Subscription> existing = new ArrayList<>();

	private final InMemoryStore store = new InMemoryStore();

	private final AtomicInteger sequence = new AtomicInteger();

	private final IngestEventUseCase useCase = new IngestEventUseCase(clientId -> existing.stream()
		.filter(subscription -> subscription.clientId().equals(clientId))
		.toList(), store, () -> "N" + sequence.incrementAndGet(), Clock.fixed(NOW, ZoneOffset.UTC));

	@Test
	void createsOnePendingFirstRoundNotificationPerMatchingSubscription() {
		existing.add(subscription("S1", "CLIENT001", "credit_deposit"));
		existing.add(subscription("S2", "CLIENT001", "credit_deposit"));

		IngestionResult result = useCase.ingest("E1", "CLIENT001", "credit_deposit", "content");

		assertThat(result.eventId()).isEqualTo("E1");
		assertThat(result.notificationEventIds()).containsExactly("N1", "N2");
		assertThat(store.notifications).extracting(PendingNotification::subscriptionId).containsExactly("S1", "S2");
		assertThat(store.notifications).allSatisfy(notification -> {
			assertThat(notification.round()).isEqualTo(1);
			assertThat(notification.clientId()).isEqualTo("CLIENT001");
			assertThat(notification.destinationUrl()).startsWith("https://example.com/");
		});
		assertThat(store.event.createdAt()).isEqualTo(NOW);
		assertThat(store.outboxCreatedAt).isEqualTo(NOW);
	}

	@Test
	void storesEventAndNotificationsInASingleStoreCall() {
		existing.add(subscription("S1", "CLIENT001", "credit_deposit"));

		useCase.ingest("E1", "CLIENT001", "credit_deposit", "content");

		assertThat(store.calls).isEqualTo(1);
	}

	@Test
	void storesTheEventWithoutNotificationsWhenNothingMatches() {
		existing.add(subscription("S1", "CLIENT001", "other_type"));

		IngestionResult result = useCase.ingest("E1", "CLIENT001", "credit_deposit", "content");

		assertThat(result.notificationEventIds()).isEmpty();
		assertThat(store.event).isNotNull();
		assertThat(store.notifications).isEmpty();
	}

	@Test
	void ignoresSubscriptionsOfOtherClients() {
		existing.add(subscription("S1", "CLIENT002", "credit_deposit"));

		assertThat(useCase.ingest("E1", "CLIENT001", "credit_deposit", "content").notificationEventIds()).isEmpty();
	}

	@Test
	void rejectsAnInvalidEventWithoutStoringAnything() {
		assertThatThrownBy(() -> useCase.ingest("E1", "", "credit_deposit", "content"))
			.isInstanceOf(EventValidationException.class);

		assertThat(store.calls).isZero();
	}

	@Test
	void reportsADuplicateEvent() {
		useCase.ingest("E1", "CLIENT001", "credit_deposit", "content");

		assertThatThrownBy(() -> useCase.ingest("E1", "CLIENT001", "credit_deposit", "content"))
			.isInstanceOf(DuplicateEventException.class);
	}

	private static Subscription subscription(String id, String clientId, String type) {
		return new Subscription(id, clientId, "https://example.com/" + id, Set.of(type), true);
	}

	private static final class InMemoryStore implements EventStorePort {

		private final Set<String> eventIds = new HashSet<>();

		private Event event;

		private List<PendingNotification> notifications = List.of();

		private Instant outboxCreatedAt;

		private int calls;

		@Override
		public void store(Event event, List<PendingNotification> notifications, Instant outboxCreatedAt) {
			calls++;
			if (!eventIds.add(event.id())) {
				throw new DuplicateEventException(event.id());
			}
			this.event = event;
			this.notifications = notifications;
			this.outboxCreatedAt = outboxCreatedAt;
		}

	}

}
