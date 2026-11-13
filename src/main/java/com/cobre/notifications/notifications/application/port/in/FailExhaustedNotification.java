package com.cobre.notifications.notifications.application.port.in;

import com.cobre.notifications.notifications.application.DispatchMessage;

/**
 * Called for every dead-lettered dispatch message, as a fallback: the last retryable attempt of a round already fails
 * its notification event in {@link DeliverNotification#deliver}, so this only acts on events that are still open.
 */
public interface FailExhaustedNotification {

	/**
	 * Marks the notification event {@code failed} if it is still open; ignored if it is already final or the message is
	 * from an earlier round.
	 */
	void failExhausted(DispatchMessage message);

}
