package com.cobre.notifications.subscriptions.application.port.in;

import java.util.Optional;

import com.cobre.notifications.subscriptions.domain.ClientId;
import com.cobre.notifications.subscriptions.domain.Subscription;
import com.cobre.notifications.subscriptions.domain.SubscriptionId;

public interface GetSubscription {

	/**
	 * Empty when the subscription does not exist or belongs to another client; callers cannot tell the two apart.
	 */
	Optional<Subscription> get(ClientId clientId, SubscriptionId id);

}
