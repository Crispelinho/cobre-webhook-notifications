package com.cobre.notifications.subscriptions.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SubscriptionDomainTests {

	@Test
	void eventTypesKeepOrderAndDropDuplicates() {
		assertThat(new EventTypes(List.of("credit_deposit", "debit_withdrawal", "credit_deposit")).values())
			.containsExactly("credit_deposit", "debit_withdrawal");
	}

	@Test
	void eventTypesMustNotBeEmpty() {
		assertThatExceptionOfType(InvalidSubscriptionException.class).isThrownBy(() -> new EventTypes(List.of()));
		assertThatExceptionOfType(InvalidSubscriptionException.class).isThrownBy(() -> new EventTypes(null));
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "Credit_Deposit", "credit-deposit", "credit deposit", "crédito" })
	void invalidEventTypesAreRejected(String eventType) {
		assertThatExceptionOfType(InvalidSubscriptionException.class)
			.isThrownBy(() -> new EventTypes(List.of(eventType)));
	}

	@Test
	void eventTypeLongerThan100CharactersIsRejected() {
		assertThat(new EventTypes(List.of("a".repeat(100))).values()).hasSize(1);
		assertThatExceptionOfType(InvalidSubscriptionException.class)
			.isThrownBy(() -> new EventTypes(List.of("a".repeat(101))));
	}

	@Test
	void nullEventTypeIsRejected() {
		assertThatExceptionOfType(InvalidSubscriptionException.class)
			.isThrownBy(() -> new EventTypes(Arrays.asList("credit_deposit", null)));
	}

	@Test
	void tooManyEventTypesAreRejected() {
		List<String> values = new ArrayList<>();
		for (int i = 0; i <= EventTypes.MAX_SIZE; i++) {
			values.add("type_" + i);
		}

		assertThatExceptionOfType(InvalidSubscriptionException.class).isThrownBy(() -> new EventTypes(values));
	}

	@Test
	void validationMessagesDoNotEchoInput() {
		assertThatExceptionOfType(InvalidSubscriptionException.class)
			.isThrownBy(() -> new EventTypes(List.of("<script>")))
			.withMessageNotContaining("<script>");
	}

	@Test
	void urlIsRequiredAndBounded() {
		assertThatExceptionOfType(InvalidSubscriptionException.class).isThrownBy(() -> new WebhookUrl(null));
		assertThatExceptionOfType(InvalidSubscriptionException.class).isThrownBy(() -> new WebhookUrl(" "));
		assertThatExceptionOfType(InvalidSubscriptionException.class)
			.isThrownBy(() -> new WebhookUrl("https://a.example.org/" + "x".repeat(WebhookUrl.MAX_LENGTH)));
	}

	@Test
	void generatedIdsAreTimeOrderedUuidV7() {
		Instant now = Instant.parse("2026-10-28T12:00:00Z");

		UUID id = UUID.fromString(SubscriptionId.generate(now).value());
		UUID later = UUID.fromString(SubscriptionId.generate(now.plusMillis(1)).value());

		assertThat(id.version()).isEqualTo(7);
		assertThat(id.variant()).isEqualTo(2);
		assertThat(id.getMostSignificantBits() >>> 16).isEqualTo(now.toEpochMilli());
		assertThat(id.toString()).isLessThan(later.toString());
	}

	@Test
	void newSubscriptionIsActive() {
		Subscription subscription = Subscription.create(new SubscriptionId("S1"), new ClientId("CLIENT001"),
				new WebhookUrl("https://hooks.example.org/cobre"), new EventTypes(Collections.nCopies(1, "a")),
				Instant.now());

		assertThat(subscription.active()).isTrue();
	}

}
