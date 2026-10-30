package com.cobre.notifications.notifications.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class NotificationFilterTests {

	private static final Instant TEN = Instant.parse("2024-03-15T10:00:00Z");

	private static final Instant SIXTEEN = Instant.parse("2024-03-15T16:00:00Z");

	@Test
	void createdFromLaterThanCreatedToIsRejected() {
		assertThatIllegalArgumentException().isThrownBy(() -> new NotificationFilter(SIXTEEN, TEN, null));
	}

	@Test
	void equalBoundsAreAllowedAndSelectAnEmptyRange() {
		assertThat(new NotificationFilter(TEN, TEN, null).createdFrom()).isEqualTo(TEN);
	}

	@Test
	void everyCriterionIsOptional() {
		assertThat(NotificationFilter.none()).isEqualTo(new NotificationFilter(null, null, null));
	}

}
