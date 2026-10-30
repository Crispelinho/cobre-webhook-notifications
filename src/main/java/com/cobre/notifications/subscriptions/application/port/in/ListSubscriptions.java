package com.cobre.notifications.subscriptions.application.port.in;

import java.util.List;

import com.cobre.notifications.kernel.domain.ClientId;
import com.cobre.notifications.subscriptions.domain.Subscription;

public interface ListSubscriptions {

	/**
	 * Active and inactive subscriptions of the client, newest first; at most
	 * {@link com.cobre.notifications.subscriptions.application.SubscriptionService#LIST_LIMIT}.
	 */
	List<Subscription> list(ClientId clientId);

}
