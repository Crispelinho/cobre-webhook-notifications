package com.cobre.notifications.shared.ids;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;

/**
 * Time-ordered UUIDs (RFC 9562 version 7): 48-bit millisecond timestamp followed by random bits, so inserts keep index
 * locality.
 */
public final class UuidV7 {

	private static final SecureRandom RANDOM = new SecureRandom();

	private UuidV7() {
	}

	public static UUID generate() {
		return generate(Instant.now());
	}

	static UUID generate(Instant timestamp) {
		long millis = timestamp.toEpochMilli();
		long randA = RANDOM.nextLong() & 0x0FFFL;
		long msb = (millis << 16) | 0x7000L | randA;
		long lsb = (RANDOM.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
		return new UUID(msb, lsb);
	}

}
