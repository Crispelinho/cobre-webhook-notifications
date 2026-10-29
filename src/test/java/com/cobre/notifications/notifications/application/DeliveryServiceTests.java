package com.cobre.notifications.notifications.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.cobre.notifications.notifications.application.port.out.DeliveryMetrics;
import com.cobre.notifications.notifications.application.port.out.DeliveryStore;
import com.cobre.notifications.notifications.application.port.out.WebhookSender;
import com.cobre.notifications.notifications.domain.AttemptResult;
import com.cobre.notifications.notifications.domain.Delivery;
import com.cobre.notifications.notifications.domain.DeliveryAttempt;
import com.cobre.notifications.notifications.domain.DeliveryOutcome;
import com.cobre.notifications.notifications.domain.DeliveryStatus;
import com.cobre.notifications.notifications.domain.NotificationEventId;
import com.cobre.notifications.notifications.domain.TransportError;

class DeliveryServiceTests {

	private static final Instant NOW = Instant.parse("2026-01-01T00:00:10Z");

	private final FakeStore store = new FakeStore();

	private final List<Delivery> sent = new ArrayList<>();

	private AttemptResult nextResult = AttemptResult.response(NOW, 200, 20);

	private final WebhookSender sender = delivery -> {
		sent.add(delivery);
		return nextResult;
	};

	private final DeliveryService service = new DeliveryService(store, sender, Clock.fixed(NOW, ZoneOffset.UTC), 5);

	@Test
	void aSuccessfulAttemptIsRecordedAndCompletesTheEvent() {
		store.current = delivery(DeliveryStatus.PENDING, 1, 0);

		assertThat(service.deliver(new DispatchMessage("N1", 1))).isEqualTo(DeliveryResult.DELIVERED);

		assertThat(sent).hasSize(1);
		assertThat(store.attempts).singleElement().satisfies(attempt -> {
			assertThat(attempt.round()).isEqualTo(1);
			assertThat(attempt.attemptNumber()).isEqualTo(1);
			assertThat(attempt.httpStatus()).isEqualTo(200);
			assertThat(attempt.errorType()).isNull();
		});
		assertThat(store.current.status()).isEqualTo(DeliveryStatus.COMPLETED);
	}

	@Test
	void aRetryableFailureAsksTheTransportToRetryAndKeepsTheEventOpen() {
		store.current = delivery(DeliveryStatus.PENDING, 1, 0);
		nextResult = AttemptResult.response(NOW, 503, 20);

		assertThat(service.deliver(new DispatchMessage("N1", 1))).isEqualTo(DeliveryResult.RETRY);

		assertThat(store.current.status()).isEqualTo(DeliveryStatus.RETRYING);
		assertThat(store.attempts).singleElement().extracting(DeliveryAttempt::errorType).isEqualTo("http_503");
	}

	@Test
	void aPermanentFailureIsRecordedAndStops() {
		store.current = delivery(DeliveryStatus.PENDING, 1, 0);
		nextResult = AttemptResult.response(NOW, 404, 20);

		assertThat(service.deliver(new DispatchMessage("N1", 1))).isEqualTo(DeliveryResult.FAILED);

		assertThat(store.current.status()).isEqualTo(DeliveryStatus.FAILED);
		assertThat(store.attempts).hasSize(1);
	}

	@Test
	void theAttemptNumberContinuesFromTheAttemptsAlreadyRecordedInTheRound() {
		store.current = delivery(DeliveryStatus.RETRYING, 1, 2);
		nextResult = AttemptResult.failure(NOW, TransportError.TIMEOUT, 10_000);

		service.deliver(new DispatchMessage("N1", 1));

		assertThat(store.attempts).singleElement().satisfies(attempt -> {
			assertThat(attempt.attemptNumber()).isEqualTo(3);
			assertThat(attempt.errorType()).isEqualTo("timeout");
			assertThat(attempt.httpStatus()).isNull();
		});
	}

	@Test
	void timeoutsAreRetriedButTheFiveAttemptsPerRoundLimitStillApplies() {
		store.current = delivery(DeliveryStatus.PENDING, 1, 0);
		nextResult = AttemptResult.failure(NOW, TransportError.TIMEOUT, 10_000);

		for (int attempt = 1; attempt <= 5; attempt++) {
			// A timeout is retryable and never completes the event, however late the receiver finally answers.
			assertThat(service.deliver(new DispatchMessage("N1", 1))).isEqualTo(DeliveryResult.RETRY);
			assertThat(store.current.status()).isEqualTo(DeliveryStatus.RETRYING);
			assertThat(store.attempts).hasSize(attempt);
		}
		assertThat(store.attempts).allSatisfy(attempt -> assertThat(attempt.errorType()).isEqualTo("timeout"));

		// The sixth message (a duplicate or the transport's last retry) sends nothing and fails the event.
		assertThat(service.deliver(new DispatchMessage("N1", 1))).isEqualTo(DeliveryResult.FAILED);

		assertThat(sent).hasSize(5);
		assertThat(store.attempts).hasSize(5);
		assertThat(store.current.status()).isEqualTo(DeliveryStatus.FAILED);
		assertThat(store.current.lastError()).isEqualTo("timeout");
	}

	@Test
	void aCompletedEventIsIgnoredWithoutSendingOrRecording() {
		store.current = delivery(DeliveryStatus.COMPLETED, 1, 1);

		assertThat(service.deliver(new DispatchMessage("N1", 1))).isEqualTo(DeliveryResult.IGNORED);

		assertThat(sent).isEmpty();
		assertThat(store.attempts).isEmpty();
	}

	@Test
	void aFailedEventIsIgnoredToo() {
		store.current = delivery(DeliveryStatus.FAILED, 1, 1);

		assertThat(service.deliver(new DispatchMessage("N1", 1))).isEqualTo(DeliveryResult.IGNORED);

		assertThat(sent).isEmpty();
	}

	@Test
	void aMessageFromAnEarlierRoundIsIgnored() {
		store.current = delivery(DeliveryStatus.PENDING, 2, 0);

		assertThat(service.deliver(new DispatchMessage("N1", 1))).isEqualTo(DeliveryResult.IGNORED);

		assertThat(sent).isEmpty();
		assertThat(store.attempts).isEmpty();
	}

	@Test
	void aMessageForAnUnknownEventIsIgnored() {
		assertThat(service.deliver(new DispatchMessage("MISSING", 1))).isEqualTo(DeliveryResult.IGNORED);

		assertThat(sent).isEmpty();
	}

	@Test
	void aConsumerThatLosesTheVersionRaceRecordsNothingAndAsksForNoRetry() {
		store.current = delivery(DeliveryStatus.PENDING, 1, 0);
		store.rejectNextWrite = true;
		nextResult = AttemptResult.response(NOW, 503, 20);

		assertThat(service.deliver(new DispatchMessage("N1", 1))).isEqualTo(DeliveryResult.IGNORED);

		assertThat(store.attempts).isEmpty();
	}

	@Test
	void anEventWhoseAttemptsAreAlreadyUsedFailsWithoutSendingAgain() {
		store.current = delivery(DeliveryStatus.RETRYING, 1, 5);

		assertThat(service.deliver(new DispatchMessage("N1", 1))).isEqualTo(DeliveryResult.FAILED);

		assertThat(sent).isEmpty();
		assertThat(store.current.status()).isEqualTo(DeliveryStatus.FAILED);
	}

	@Test
	void theDeadLetterHandlerFailsAnOpenEvent() {
		store.current = delivery(DeliveryStatus.RETRYING, 1, 5);

		service.failExhausted(new DispatchMessage("N1", 1));

		assertThat(store.current.status()).isEqualTo(DeliveryStatus.FAILED);
		assertThat(store.current.deliveryDate()).isEqualTo(NOW);
	}

	@Test
	void theDeadLetterHandlerLeavesFinalEventsAndOtherRoundsAlone() {
		store.current = delivery(DeliveryStatus.COMPLETED, 1, 1);
		service.failExhausted(new DispatchMessage("N1", 1));
		assertThat(store.current.status()).isEqualTo(DeliveryStatus.COMPLETED);

		store.current = delivery(DeliveryStatus.RETRYING, 2, 1);
		service.failExhausted(new DispatchMessage("N1", 1));
		assertThat(store.current.status()).isEqualTo(DeliveryStatus.RETRYING);
	}

	@Test
	void everyRecordedAttemptIsObservedWithItsOutcomeAndLatency() {
		RecordingMetrics metrics = new RecordingMetrics();
		DeliveryService observed = new DeliveryService(store, sender, Clock.fixed(NOW, ZoneOffset.UTC), 5, metrics);
		store.current = delivery(DeliveryStatus.PENDING, 1, 0);
		nextResult = AttemptResult.response(NOW, 503, 120);

		observed.deliver(new DispatchMessage("N1", 1));

		assertThat(metrics.attempts).containsExactly(DeliveryOutcome.RETRYABLE + ":120");
		assertThat(metrics.finals).isEmpty();
	}

	@Test
	void theTimeFromCreationToTheFinalOutcomeIsObservedWhenTheEventCompletes() {
		RecordingMetrics metrics = new RecordingMetrics();
		DeliveryService observed = new DeliveryService(store, sender,
				Clock.fixed(NOW.plusSeconds(90), ZoneOffset.UTC), 5, metrics);
		store.current = delivery(DeliveryStatus.PENDING, 1, 0);

		observed.deliver(new DispatchMessage("N1", 1));

		assertThat(metrics.attempts).containsExactly(DeliveryOutcome.SUCCESS + ":20");
		assertThat(metrics.finals).containsExactly(DeliveryStatus.COMPLETED + ":" + Duration.ofSeconds(90));
	}

	@Test
	void exhaustedRetriesObserveAFailedFinalOutcome() {
		RecordingMetrics metrics = new RecordingMetrics();
		DeliveryService observed = new DeliveryService(store, sender,
				Clock.fixed(NOW.plusSeconds(300), ZoneOffset.UTC), 5, metrics);
		store.current = delivery(DeliveryStatus.RETRYING, 1, 5);

		observed.failExhausted(new DispatchMessage("N1", 1));

		assertThat(metrics.finals).containsExactly(DeliveryStatus.FAILED + ":" + Duration.ofSeconds(300));
		assertThat(metrics.attempts).isEmpty();
	}

	@Test
	void anAttemptLostToAConcurrentConsumerIsNotObserved() {
		RecordingMetrics metrics = new RecordingMetrics();
		DeliveryService observed = new DeliveryService(store, sender, Clock.fixed(NOW, ZoneOffset.UTC), 5, metrics);
		store.current = delivery(DeliveryStatus.PENDING, 1, 0);
		store.rejectNextWrite = true;

		observed.deliver(new DispatchMessage("N1", 1));

		assertThat(metrics.attempts).isEmpty();
		assertThat(metrics.finals).isEmpty();
	}

	@Test
	void rejectsANonPositiveAttemptBudget() {
		assertThatIllegalArgumentException().isThrownBy(() -> new DeliveryService(store, sender, Clock.systemUTC(), 0));
	}

	private static Delivery delivery(DeliveryStatus status, int round, int attemptsInRound) {
		return new Delivery(new NotificationEventId("N1"), "E1", "credit_deposit", "content",
				"https://example.com/hook", NOW, status, round, attemptsInRound, null, null, null, 0);
	}

	private static final class RecordingMetrics implements DeliveryMetrics {

		private final List<String> attempts = new ArrayList<>();

		private final List<String> finals = new ArrayList<>();

		@Override
		public void attemptRecorded(DeliveryOutcome outcome, long latencyMs) {
			attempts.add(outcome + ":" + latencyMs);
		}

		@Override
		public void finalOutcome(DeliveryStatus status, Duration sinceCreation) {
			finals.add(status + ":" + sinceCreation);
		}

		@Override
		public void deadLettered() {
		}

	}

	private static final class FakeStore implements DeliveryStore {

		private Delivery current;

		private final List<DeliveryAttempt> attempts = new ArrayList<>();

		private boolean rejectNextWrite;

		@Override
		public Optional<Delivery> find(String notificationEventId) {
			return current != null && current.id().value().equals(notificationEventId) ? Optional.of(current)
					: Optional.empty();
		}

		@Override
		public boolean recordAttempt(Delivery before, DeliveryAttempt attempt, Delivery after) {
			if (rejectNextWrite) {
				rejectNextWrite = false;
				return false;
			}
			attempts.add(attempt);
			current = after;
			return true;
		}

		@Override
		public boolean updateState(Delivery before, Delivery after) {
			current = after;
			return true;
		}

	}

}
