package com.cobre.notifications.notifications.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.cobre.notifications.audit.application.AuditEntry;
import com.cobre.notifications.audit.application.AuditOutcome;
import com.cobre.notifications.notifications.application.port.out.ReplayStore;
import com.cobre.notifications.notifications.domain.ClientId;
import com.cobre.notifications.notifications.domain.DeliveryStatus;
import com.cobre.notifications.notifications.domain.NotReplayableException;
import com.cobre.notifications.notifications.domain.NotificationEvent;
import com.cobre.notifications.notifications.domain.NotificationEventId;
import com.cobre.notifications.support.RecordingAuditLog;

class ReplayNotificationEventUseCaseTests {

	private static final Instant NOW = Instant.parse("2024-04-01T10:00:00Z");

	private static final ClientId OWNER = new ClientId("CLIENT002");

	private static final NotificationEventId ID = new NotificationEventId("EVT003");

	private final FakeReplayStore store = new FakeReplayStore();

	private final RecordingAuditLog audit = new RecordingAuditLog();

	private final ReplayNotificationEventUseCase useCase = new ReplayNotificationEventUseCase(store,
			Clock.fixed(NOW, ZoneOffset.UTC), audit);

	@Test
	void aReplayIsAuditedOnceWithTheActingClientAndTheResource() {
		store.stored = event(OWNER, DeliveryStatus.FAILED, 1);

		useCase.replay(OWNER, ID);

		assertThat(audit.entries)
			.containsExactly(new AuditEntry("CLIENT002", "notification_event.replay", "EVT003", AuditOutcome.SUCCESS));
	}

	@Test
	void aRejectedReplayOfACompletedEventIsAuditedAsRejected() {
		store.stored = event(OWNER, DeliveryStatus.COMPLETED, 1);

		assertThatThrownBy(() -> useCase.replay(OWNER, ID)).isInstanceOf(NotReplayableException.class);

		assertThat(audit.entries)
			.containsExactly(new AuditEntry("CLIENT002", "notification_event.replay", "EVT003", AuditOutcome.REJECTED));
	}

	@Test
	void losingTheReplayRaceIsAuditedAsRejected() {
		store.stored = event(OWNER, DeliveryStatus.FAILED, 1);
		store.acceptWrites = false;

		assertThatThrownBy(() -> useCase.replay(OWNER, ID)).isInstanceOf(NotReplayableException.class);

		assertThat(audit.entries).singleElement().extracting(AuditEntry::outcome).isEqualTo(AuditOutcome.REJECTED);
	}

	@Test
	void aReplayOfAnEventOfAnotherClientIsAuditedAsNotFound() {
		store.stored = event(OWNER, DeliveryStatus.FAILED, 1);

		useCase.replay(new ClientId("CLIENT001"), ID);

		assertThat(audit.entries).containsExactly(
				new AuditEntry("CLIENT001", "notification_event.replay", "EVT003", AuditOutcome.NOT_FOUND));
	}

	@Test
	void replaysAFailedEventOfTheClient() {
		store.stored = event(OWNER, DeliveryStatus.FAILED, 1);

		Optional<NotificationEvent> result = useCase.replay(OWNER, ID);

		assertThat(result).hasValueSatisfying(replayed -> {
			assertThat(replayed.round()).isEqualTo(2);
			assertThat(replayed.deliveryStatus()).isEqualTo(DeliveryStatus.PENDING);
			assertThat(replayed.deliveryDate()).isNull();
		});
		assertThat(store.started).hasSize(1);
		assertThat(store.startedAt).isEqualTo(NOW);
		assertThat(store.started.getFirst().round()).isEqualTo(2);
	}

	@Test
	void anEventOfAnotherClientIsNotFoundAndNothingIsWritten() {
		store.stored = event(OWNER, DeliveryStatus.FAILED, 1);

		assertThat(useCase.replay(new ClientId("CLIENT001"), ID)).isEmpty();
		assertThat(store.started).isEmpty();
	}

	@Test
	void aMissingEventIsNotFound() {
		assertThat(useCase.replay(OWNER, ID)).isEmpty();
	}

	@Test
	void anEventThatIsNotFailedIsRejectedAndNothingIsWritten() {
		store.stored = event(OWNER, DeliveryStatus.COMPLETED, 1);

		assertThatThrownBy(() -> useCase.replay(OWNER, ID)).isInstanceOf(NotReplayableException.class);
		assertThat(store.started).isEmpty();
	}

	@Test
	void losingTheVersionRaceIsRejected() {
		store.stored = event(OWNER, DeliveryStatus.FAILED, 1);
		store.acceptWrites = false;

		assertThatThrownBy(() -> useCase.replay(OWNER, ID)).isInstanceOf(NotReplayableException.class);
		assertThat(store.started).isEmpty();
	}

	private static NotificationEvent event(ClientId clientId, DeliveryStatus status, int round) {
		Instant createdAt = Instant.parse("2024-03-15T11:20:18Z");
		return new NotificationEvent(ID, "EVT003", clientId, "credit_transfer", "content", status, createdAt, createdAt,
				null, "http_500", round, 3, List.of());
	}

	/**
	 * Honors the port contract: lookups are restricted to the given client.
	 */
	private static final class FakeReplayStore implements ReplayStore {

		NotificationEvent stored;

		boolean acceptWrites = true;

		final List<NotificationEvent> started = new ArrayList<>();

		Instant startedAt;

		@Override
		public Optional<NotificationEvent> findById(ClientId clientId, NotificationEventId id) {
			return Optional.ofNullable(stored).filter(e -> e.clientId().equals(clientId) && e.id().equals(id));
		}

		@Override
		public boolean startReplay(NotificationEvent before, NotificationEvent after, Instant now) {
			if (!acceptWrites) {
				return false;
			}
			started.add(after);
			startedAt = now;
			return true;
		}

	}

}
