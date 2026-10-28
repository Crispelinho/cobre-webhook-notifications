package com.cobre.notifications.subscriptions.application.port.in;

import com.cobre.notifications.subscriptions.application.CreatedSubscription;
import com.cobre.notifications.subscriptions.domain.ClientId;
import com.cobre.notifications.subscriptions.domain.EventTypes;
import com.cobre.notifications.subscriptions.domain.WebhookUrl;

public interface CreateSubscription {

	/**
	 * Registers an active subscription owned by the given client.
	 * @throws com.cobre.notifications.subscriptions.domain.InvalidSubscriptionException if the destination is not
	 * allowed; nothing is stored
	 */
	CreatedSubscription create(ClientId clientId, WebhookUrl url, EventTypes eventTypes);

}
