package com.cobre.notifications.notifications.domain;

import java.time.Instant;

/**
 * Optional criteria for listing notification events. {@code createdFrom} is inclusive, {@code createdTo} exclusive.
 */
public record NotificationFilter(Instant createdFrom, Instant createdTo, DeliveryStatus deliveryStatus) {

	public NotificationFilter {
		if (createdFrom != null && createdTo != null && createdFrom.isAfter(createdTo)) {
			throw new IllegalArgumentException("created_from must not be later than created_to");
		}
	}

	public static NotificationFilter none() {
		return new NotificationFilter(null, null, null);
	}

}
