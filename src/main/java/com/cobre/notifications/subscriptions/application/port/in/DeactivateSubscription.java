package com.cobre.notifications.subscriptions.application.port.in;

import com.cobre.notifications.kernel.domain.ClientId;
import com.cobre.notifications.subscriptions.domain.SubscriptionId;

public interface DeactivateSubscription {

	/**
	 * Marks the subscription inactive (logical deletion); deactivating it twice is a no-op. Notification events
	 * already created keep their history.
	 * @return false when the subscription does not exist or belongs to another client
	 */
	boolean deactivate(ClientId clientId, SubscriptionId id);

}
