package com.cobre.notifications.notifications.domain;

import com.cobre.notifications.kernel.domain.ClientId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class NotificationEventReplayTests {

	private static final Instant CREATED = Instant.parse("2024-03-15T09:30:22Z");

	private static final Instant DELIVERED = Instant.parse("2024-03-15T09:31:00Z");

	@Test
	void aFailedEventMovesToTheNextRoundAsPending() {
		NotificationEvent replayed = event(DeliveryStatus.FAILED, 1, null).replay();

		assertThat(replayed.round()).isEqualTo(2);
		assertThat(replayed.deliveryStatus()).isEqualTo(DeliveryStatus.PENDING);
	}

	@Test
	void replayClearsTheDeliveryDateOfTheFinishedRound() {
		assertThat(event(DeliveryStatus.FAILED, 1, null).replay().deliveryDate()).isNull();
	}

	@Test
	void replayKeepsDeliveredAtIdentityHistoryAndVersion() {
		DeliveryAttempt attempt = new DeliveryAttempt(1, 1, CREATED, 500, "http_500", 10);
		NotificationEvent failed = new NotificationEvent(new NotificationEventId("EVT003"), "EVT003",
				new ClientId("CLIENT002"), "credit_transfer", "content", DeliveryStatus.FAILED, CREATED, CREATED,
				DELIVERED, "http_500", 1, 7, List.of(attempt));

		NotificationEvent replayed = failed.replay();

		assertThat(replayed.id()).isEqualTo(failed.id());
		assertThat(replayed.deliveredAt()).isEqualTo(DELIVERED);
		assertThat(replayed.attempts()).containsExactly(attempt);
		assertThat(replayed.version()).isEqualTo(7);
	}

	@Test
	void replayClearsTheLastErrorOfTheFinishedRound() {
		assertThat(event(DeliveryStatus.FAILED, 1, null).lastError()).isEqualTo("http_500");
		assertThat(event(DeliveryStatus.FAILED, 1, null).replay().lastError()).isNull();
	}

	@Test
	void roundsKeepGrowingOnEveryReplay() {
		assertThat(event(DeliveryStatus.FAILED, 4, null).replay().round()).isEqualTo(5);
	}

	@ParameterizedTest
	@EnumSource(value = DeliveryStatus.class, names = "FAILED", mode = EnumSource.Mode.EXCLUDE)
	void anEventThatIsNotFailedCannotBeReplayed(DeliveryStatus status) {
		assertThatThrownBy(() -> event(status, 1, null).replay()).isInstanceOf(NotReplayableException.class)
			.hasMessage("Only a failed notification event can be replayed.");
	}

	private static NotificationEvent event(DeliveryStatus status, int round, Instant deliveredAt) {
		return new NotificationEvent(new NotificationEventId("EVT003"), "EVT003", new ClientId("CLIENT002"),
				"credit_transfer", "content", status, CREATED, CREATED, deliveredAt, "http_500", round, 0, List.of());
	}

}
