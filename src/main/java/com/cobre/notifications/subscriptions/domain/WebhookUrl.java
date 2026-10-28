package com.cobre.notifications.subscriptions.domain;

/**
 * Where a subscription's webhooks are sent. This type only checks presence and length; whether the destination is
 * safe to call is decided by the destination policy behind {@code DestinationValidator}.
 */
public record WebhookUrl(String value) {

	public static final int MAX_LENGTH = 2048;

	public WebhookUrl {
		if (value == null || value.isBlank()) {
			throw new InvalidSubscriptionException("url is required");
		}
		if (value.length() > MAX_LENGTH) {
			throw new InvalidSubscriptionException("url must be at most " + MAX_LENGTH + " characters");
		}
	}

}
