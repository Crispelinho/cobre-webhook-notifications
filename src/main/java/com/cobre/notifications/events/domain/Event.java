package com.cobre.notifications.events.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A platform event reported by an internal producer. Invariants are enforced on construction, so an invalid event
 * cannot exist. Error messages never echo the rejected value.
 */
public record Event(String id, String clientId, String eventType, String content, Instant createdAt) {

	public static final int MAX_ID_LENGTH = 64;

	public static final int MAX_CONTENT_LENGTH = 10_000;

	private static final Pattern EVENT_TYPE = Pattern.compile("^[a-z0-9_]{1,100}$");

	public Event {
		id = requireBoundedText(id, "event_id");
		clientId = requireBoundedText(clientId, "client_id");
		if (eventType == null || !EVENT_TYPE.matcher(eventType).matches()) {
			throw new IllegalArgumentException("event_type must match ^[a-z0-9_]{1,100}$.");
		}
		if (content == null || content.isBlank()) {
			throw new IllegalArgumentException("content is required.");
		}
		if (content.length() > MAX_CONTENT_LENGTH) {
			throw new IllegalArgumentException("content must have at most " + MAX_CONTENT_LENGTH + " characters.");
		}
		Objects.requireNonNull(createdAt, "created at is required");
	}

	private static String requireBoundedText(String value, String field) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(field + " is required.");
		}
		if (value.length() > MAX_ID_LENGTH) {
			throw new IllegalArgumentException(field + " must have at most " + MAX_ID_LENGTH + " characters.");
		}
		return value;
	}

}
