package com.cobre.notifications.subscriptions.domain;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record SubscriptionId(String value) {

	private static final SecureRandom RANDOM = new SecureRandom();

	public SubscriptionId {
		Objects.requireNonNull(value, "subscription id is required");
	}

	/**
	 * New UUIDv7 (RFC 9562) id: a 48-bit Unix millisecond timestamp followed by random bits, so new rows keep index
	 * locality (D09).
	 */
	public static SubscriptionId generate(Instant now) {
		long millis = now.toEpochMilli();
		long mostSignificant = (millis << 16) | 0x7000L | (RANDOM.nextInt() & 0x0FFFL);
		long leastSignificant = (RANDOM.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
		return new SubscriptionId(new UUID(mostSignificant, leastSignificant).toString());
	}

}
