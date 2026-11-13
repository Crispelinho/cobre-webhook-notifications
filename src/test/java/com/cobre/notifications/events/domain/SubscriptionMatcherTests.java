package com.cobre.notifications.events.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class SubscriptionMatcherTests {

	private static final Event EVENT = new Event("E1", "CLIENT001", "credit_deposit", "x",
			Instant.parse("2026-01-01T00:00:00Z"));

	@Test
	void matchesActiveSubscriptionsOfTheSameClientThatIncludeTheType() {
		Subscription match = subscription("S1", "CLIENT001", Set.of("credit_deposit", "other"), true);

		assertThat(SubscriptionMatcher.match(EVENT, List.of(match))).containsExactly(match);
	}

	@Test
	void skipsSubscriptionsThatDoNotIncludeTheType() {
		assertThat(SubscriptionMatcher.match(EVENT, List.of(subscription("S1", "CLIENT001", Set.of("other"), true))))
			.isEmpty();
	}

	@Test
	void neverMatchesASubscriptionOfAnotherClient() {
		assertThat(SubscriptionMatcher.match(EVENT,
				List.of(subscription("S2", "CLIENT002", Set.of("credit_deposit"), true))))
			.isEmpty();
	}

	@Test
	void skipsInactiveSubscriptions() {
		assertThat(SubscriptionMatcher.match(EVENT,
				List.of(subscription("S3", "CLIENT001", Set.of("credit_deposit"), false))))
			.isEmpty();
	}

	private static Subscription subscription(String id, String clientId, Set<String> types, boolean active) {
		return new Subscription(id, clientId, "https://example.com/" + id, types, active);
	}

}
