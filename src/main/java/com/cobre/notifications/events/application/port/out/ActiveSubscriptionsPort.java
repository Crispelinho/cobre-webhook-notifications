package com.cobre.notifications.events.application.port.out;

import java.util.List;

import com.cobre.notifications.events.domain.Subscription;

public interface ActiveSubscriptionsPort {

	/**
	 * Active subscriptions of one client.
	 */
	List<Subscription> findActiveByClientId(String clientId);

}
