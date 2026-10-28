package com.cobre.notifications.notifications.application.port.in;

import com.cobre.notifications.notifications.application.DispatchMessage;

/**
 * Called when the transport gave up on a dispatch message after the last attempt.
 */
public interface FailExhaustedNotification {

	/**
	 * Marks the notification event {@code failed}; ignored if it is already final or the message is from an earlier
	 * round.
	 */
	void failExhausted(DispatchMessage message);

}
