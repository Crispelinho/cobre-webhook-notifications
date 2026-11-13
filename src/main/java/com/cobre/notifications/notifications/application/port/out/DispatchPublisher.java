package com.cobre.notifications.notifications.application.port.out;

import com.cobre.notifications.notifications.application.DispatchMessage;
import com.cobre.notifications.notifications.application.DispatchPublishException;

/**
 * Sends a dispatch message to the transport and returns only once the transport has confirmed it.
 */
public interface DispatchPublisher {

	/**
	 * @throws DispatchPublishException if the transport did not confirm the message in time
	 */
	void publish(DispatchMessage message) throws DispatchPublishException;

}
