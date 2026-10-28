package com.cobre.notifications.subscriptions.application.port.in;

import java.util.List;

import com.cobre.notifications.subscriptions.domain.ClientId;
import com.cobre.notifications.subscriptions.domain.Subscription;

public interface ListSubscriptions {

	/**
	 * Active and inactive subscriptions of the client, newest first.
	 */
	List<Subscription> list(ClientId clientId);

}
