package com.cobre.notifications.notifications.domain;

import java.util.Arrays;
import java.util.Locale;

public enum DeliveryStatus {

	PENDING, RETRYING, COMPLETED, FAILED;

	/**
	 * Value used in the API and the database (lowercase).
	 */
	public String wireValue() {
		return name().toLowerCase(Locale.ROOT);
	}

	public static DeliveryStatus fromWireValue(String value) {
		return Arrays.stream(values())
			.filter(status -> status.wireValue().equals(value))
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException("unknown delivery status: " + value));
	}

}
