package com.cobre.notifications.subscriptions.domain;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The event types a subscription listens to: at least one, at most {@value #MAX_SIZE}, each matching
 * {@code ^[a-z0-9_]{1,100}$}. Duplicates are dropped, keeping the first occurrence.
 */
public record EventTypes(List<String> values) {

	public static final int MAX_SIZE = 50;

	private static final Pattern EVENT_TYPE = Pattern.compile("[a-z0-9_]{1,100}");

	public EventTypes {
		if (values == null || values.isEmpty()) {
			throw new InvalidSubscriptionException("event_types must contain at least one event type");
		}
		if (values.size() > MAX_SIZE) {
			throw new InvalidSubscriptionException("event_types must contain at most " + MAX_SIZE + " event types");
		}
		for (String value : values) {
			if (value == null || !EVENT_TYPE.matcher(value).matches()) {
				throw new InvalidSubscriptionException(
						"each event type must be 1 to 100 lowercase letters, digits or underscores");
			}
		}
		values = List.copyOf(new LinkedHashSet<>(values));
	}

}
