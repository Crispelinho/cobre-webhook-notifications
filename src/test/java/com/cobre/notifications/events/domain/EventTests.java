package com.cobre.notifications.events.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class EventTests {

	private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

	@Test
	void acceptsAValidEvent() {
		Event event = new Event("E1", "CLIENT001", "credit_deposit", "Deposit of $10", NOW);

		assertThat(event.id()).isEqualTo("E1");
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = { "   ", "Credit_Deposit", "credit-deposit", "credit deposit", "credit_dépôt" })
	void rejectsAnInvalidEventType(String eventType) {
		assertThatThrownBy(() -> new Event("E1", "CLIENT001", eventType, "x", NOW))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void eventTypeLengthBoundaryIs100() {
		assertThatCode(() -> new Event("E1", "C", "a".repeat(100), "x", NOW)).doesNotThrowAnyException();
		assertThatThrownBy(() -> new Event("E1", "C", "a".repeat(101), "x", NOW))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = " ")
	void rejectsAMissingEventIdOrClientIdOrContent(String blank) {
		assertThatThrownBy(() -> new Event(blank, "C", "t", "x", NOW)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new Event("E", blank, "t", "x", NOW)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new Event("E", "C", "t", blank, NOW)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void identifierLengthBoundaryIs64() {
		assertThatCode(() -> new Event("e".repeat(64), "c".repeat(64), "t", "x", NOW)).doesNotThrowAnyException();
		assertThatThrownBy(() -> new Event("e".repeat(65), "C", "t", "x", NOW))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new Event("E", "c".repeat(65), "t", "x", NOW))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void contentLengthBoundaryIs10000() {
		assertThatCode(() -> new Event("E", "C", "t", "x".repeat(10_000), NOW)).doesNotThrowAnyException();
		assertThatThrownBy(() -> new Event("E", "C", "t", "x".repeat(10_001), NOW))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void errorMessagesDoNotEchoTheRejectedValue() {
		assertThatThrownBy(() -> new Event("E", "C", "<script>", "x", NOW))
			.hasMessageNotContaining("<script>");
	}

}
