package com.cobre.notifications.notifications.application.port.out;

import com.cobre.notifications.notifications.domain.AttemptResult;
import com.cobre.notifications.notifications.domain.Delivery;

/**
 * Sends one notification event to its destination.
 */
public interface WebhookSender {

	/**
	 * Performs exactly one request. Every failure (timeout, connection, TLS, invalid destination, non-2xx answer) is
	 * reported in the result; the method does not throw for them.
	 */
	AttemptResult send(Delivery delivery);

}
