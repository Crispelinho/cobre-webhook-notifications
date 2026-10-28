package com.cobre.notifications.subscriptions.application.port.out;

import com.cobre.notifications.subscriptions.domain.WebhookUrl;

/**
 * Checks that the service may send webhooks to a URL (OWASP API7, SSRF).
 */
public interface DestinationValidator {

	/**
	 * @throws com.cobre.notifications.subscriptions.domain.InvalidSubscriptionException if the destination is not
	 * allowed
	 */
	void validate(WebhookUrl url);

}
