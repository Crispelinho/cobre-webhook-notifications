package com.cobre.notifications.notifications.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.cobre.notifications.notifications.application.port.out.NotificationQueryPort;
import com.cobre.notifications.notifications.domain.ClientId;
import com.cobre.notifications.notifications.domain.DeliveryStatus;
import com.cobre.notifications.notifications.domain.NotificationEvent;
import com.cobre.notifications.notifications.domain.NotificationEventId;
import com.cobre.notifications.notifications.domain.NotificationFilter;

class NotificationQueryServiceTests {

	private static final ClientId CLIENT_001 = new ClientId("CLIENT001");

	private static final ClientId CLIENT_002 = new ClientId("CLIENT002");

	private final InMemoryQueryPort port = new InMemoryQueryPort(List.of(notification("EVT001", CLIENT_001),
			notification("EVT003", CLIENT_002)));

	private final NotificationQueryService service = new NotificationQueryService(port);

	@Test
	void listIsScopedToTheRequestingClient() {
		var page = service.list(CLIENT_001, NotificationFilter.none(), PageQuery.firstPage());

		assertThat(page.items()).extracting(n -> n.id().value()).containsExactly("EVT001");
	}

	@Test
	void notificationEventOfAnotherClientIsNotFound() {
		assertThat(service.get(CLIENT_001, new NotificationEventId("EVT003"))).isEmpty();
	}

	@Test
	void ownNotificationEventIsFound() {
		assertThat(service.get(CLIENT_002, new NotificationEventId("EVT003"))).isPresent();
	}

	private static NotificationEvent notification(String id, ClientId clientId) {
		Instant createdAt = Instant.parse("2024-03-15T09:30:22Z");
		return new NotificationEvent(new NotificationEventId(id), id, clientId, "credit_transfer", "content",
				DeliveryStatus.COMPLETED, createdAt, createdAt, createdAt, List.of());
	}

	/**
	 * Honors the port contract: results are always restricted to the given client.
	 */
	private record InMemoryQueryPort(List<NotificationEvent> notifications) implements NotificationQueryPort {

		@Override
		public Page<NotificationEvent> findPage(ClientId clientId, NotificationFilter filter, PageQuery pageQuery) {
			var owned = notifications.stream().filter(n -> n.clientId().equals(clientId)).toList();
			return new Page<>(owned, pageQuery.page(), pageQuery.size(), owned.size());
		}

		@Override
		public Optional<NotificationEvent> findById(ClientId clientId, NotificationEventId id) {
			return notifications.stream()
				.filter(n -> n.clientId().equals(clientId) && n.id().equals(id))
				.findFirst();
		}

	}

}
