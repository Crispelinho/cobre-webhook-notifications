package com.cobre.notifications.notifications.application.port.in;

import com.cobre.notifications.notifications.application.DeliveryResult;
import com.cobre.notifications.notifications.application.DispatchMessage;

/**
 * Handles one dispatch message: one delivery attempt, or nothing if the message is stale.
 */
public interface DeliverNotification {

	DeliveryResult deliver(DispatchMessage message);

}
