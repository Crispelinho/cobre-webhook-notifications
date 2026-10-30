package com.cobre.notifications.subscriptions.application.port.in;

import java.util.Optional;

import com.cobre.notifications.subscriptions.application.SubscriptionChanges;
import com.cobre.notifications.kernel.domain.ClientId;
import com.cobre.notifications.subscriptions.domain.Subscription;
import com.cobre.notifications.subscriptions.domain.SubscriptionId;

public interface UpdateSubscription {

	/**
	 * Changes only {@code active} and the event types; the URL is immutable. Empty when the subscription does not
	 * exist or belongs to another client.
	 */
	Optional<Subscription> update(ClientId clientId, SubscriptionId id, SubscriptionChanges changes);

}
