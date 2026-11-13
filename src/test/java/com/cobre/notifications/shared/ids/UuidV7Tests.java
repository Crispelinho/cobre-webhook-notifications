package com.cobre.notifications.shared.ids;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class UuidV7Tests {

	@Test
	void hasVersion7AndRfcVariant() {
		UUID uuid = UuidV7.generate();

		assertThat(uuid.version()).isEqualTo(7);
		assertThat(uuid.variant()).isEqualTo(2);
	}

	@Test
	void embedsTheMillisecondTimestampInTheFirst48Bits() {
		Instant timestamp = Instant.parse("2026-03-04T05:06:07.089Z");

		assertThat(UuidV7.generate(timestamp).getMostSignificantBits() >>> 16).isEqualTo(timestamp.toEpochMilli());
	}

	@Test
	void sortsByCreationTime() {
		UUID earlier = UuidV7.generate(Instant.parse("2026-01-01T00:00:00Z"));
		UUID later = UuidV7.generate(Instant.parse("2026-01-01T00:00:01Z"));

		assertThat(earlier.toString()).isLessThan(later.toString());
	}

}
