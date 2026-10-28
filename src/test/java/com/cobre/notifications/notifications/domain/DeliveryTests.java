package com.cobre.notifications.notifications.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class DeliveryTests {

	private static final Instant CREATED = Instant.parse("2026-01-01T00:00:00Z");

	private static final Instant SENT = Instant.parse("2026-01-01T00:00:05Z");

	private static final Instant DONE = Instant.parse("2026-01-01T00:00:06Z");

	@Test
	void successCompletesTheEventAndStampsBothDates() {
		Delivery after = delivery(DeliveryStatus.PENDING, 0).afterAttempt(AttemptResult.response(SENT, 200, 40), DONE);

		assertThat(after.status()).isEqualTo(DeliveryStatus.COMPLETED);
		assertThat(after.deliveredAt()).isEqualTo(SENT);
		assertThat(after.deliveryDate()).isEqualTo(DONE);
		assertThat(after.attemptsInRound()).isEqualTo(1);
		assertThat(after.lastError()).isNull();
	}

	@Test
	void aRetryableFailureMovesPendingToRetryingWithoutAFinalDate() {
		Delivery after = delivery(DeliveryStatus.PENDING, 0).afterAttempt(AttemptResult.response(SENT, 503, 40), DONE);

		assertThat(after.status()).isEqualTo(DeliveryStatus.RETRYING);
		assertThat(after.deliveryDate()).isNull();
		assertThat(after.deliveredAt()).isNull();
		assertThat(after.lastError()).isEqualTo("http_503");
		assertThat(after.attemptsInRound()).isEqualTo(1);
	}

	@Test
	void aTimeoutIsRetryableAndKeepsTheErrorCode() {
		Delivery after = delivery(DeliveryStatus.PENDING, 0)
			.afterAttempt(AttemptResult.failure(SENT, TransportError.TIMEOUT, 10_000), DONE);

		assertThat(after.status()).isEqualTo(DeliveryStatus.RETRYING);
		assertThat(after.lastError()).isEqualTo("timeout");
	}

	@Test
	void aRetryingEventThatSucceedsCompletesAndClearsTheLastError() {
		Delivery after = delivery(DeliveryStatus.RETRYING, 2).afterAttempt(AttemptResult.response(SENT, 200, 40), DONE);

		assertThat(after.status()).isEqualTo(DeliveryStatus.COMPLETED);
		assertThat(after.attemptsInRound()).isEqualTo(3);
		assertThat(after.lastError()).isNull();
	}

	@Test
	void aPermanentFailureFailsTheEventAtOnce() {
		Delivery after = delivery(DeliveryStatus.PENDING, 0).afterAttempt(AttemptResult.response(SENT, 400, 40), DONE);

		assertThat(after.status()).isEqualTo(DeliveryStatus.FAILED);
		assertThat(after.deliveryDate()).isEqualTo(DONE);
		assertThat(after.deliveredAt()).isNull();
		assertThat(after.lastError()).isEqualTo("http_400");
		assertThat(after.attemptsInRound()).isEqualTo(1);
	}

	@Test
	void aRedirectFailsTheEvent() {
		Delivery after = delivery(DeliveryStatus.PENDING, 0).afterAttempt(AttemptResult.response(SENT, 302, 40), DONE);

		assertThat(after.status()).isEqualTo(DeliveryStatus.FAILED);
	}

	@Test
	void exhaustionFailsTheEventKeepingTheLastError() {
		Delivery retrying = delivery(DeliveryStatus.PENDING, 0).afterAttempt(AttemptResult.response(SENT, 500, 40),
				DONE);

		Delivery after = retrying.exhausted(DONE);

		assertThat(after.status()).isEqualTo(DeliveryStatus.FAILED);
		assertThat(after.deliveryDate()).isEqualTo(DONE);
		assertThat(after.lastError()).isEqualTo("http_500");
	}

	@Test
	void exhaustionWithoutAnyAttemptStillExplainsWhy() {
		assertThat(delivery(DeliveryStatus.PENDING, 0).exhausted(DONE).lastError()).isEqualTo("retries_exhausted");
	}

	@ParameterizedTest
	@EnumSource(value = DeliveryStatus.class, names = { "COMPLETED", "FAILED" })
	void finalStatesTakeNoMoreAttempts(DeliveryStatus status) {
		Delivery done = delivery(status, 1);

		assertThatIllegalStateException()
			.isThrownBy(() -> done.afterAttempt(AttemptResult.response(SENT, 200, 1), DONE));
		assertThatIllegalStateException().isThrownBy(() -> done.exhausted(DONE));
	}

	@ParameterizedTest
	@EnumSource(DeliveryStatus.class)
	void onlyPendingAndRetryingAreOpenAndOnlyForTheCurrentRound(DeliveryStatus status) {
		Delivery delivery = delivery(status, 1);
		boolean open = status == DeliveryStatus.PENDING || status == DeliveryStatus.RETRYING;

		assertThat(delivery.isOpenFor(2)).isEqualTo(open);
		assertThat(delivery.isOpenFor(1)).isFalse();
		assertThat(delivery.isOpenFor(3)).isFalse();
	}

	@Test
	void attemptNumbersAndBudgetFollowTheAttemptsOfTheRound() {
		Delivery delivery = delivery(DeliveryStatus.RETRYING, 4);

		assertThat(delivery.nextAttemptNumber()).isEqualTo(5);
		assertThat(delivery.hasAttemptsLeft(5)).isTrue();
		assertThat(delivery(DeliveryStatus.RETRYING, 5).hasAttemptsLeft(5)).isFalse();
	}

	@Test
	void anAttemptHasEitherAStatusOrAnErrorNeverBoth() {
		assertThatIllegalArgumentException().isThrownBy(() -> new AttemptResult(SENT, 200, TransportError.TIMEOUT, 1));
		assertThatIllegalArgumentException().isThrownBy(() -> new AttemptResult(SENT, null, null, 1));
	}

	/** Round 2, so tests also prove messages are matched against the current round. */
	private static Delivery delivery(DeliveryStatus status, int attemptsInRound) {
		return new Delivery(new NotificationEventId("N1"), "E1", "credit_deposit", "content",
				"https://example.com/hook", CREATED, status, 2, attemptsInRound, null, null, null, 7);
	}

}
