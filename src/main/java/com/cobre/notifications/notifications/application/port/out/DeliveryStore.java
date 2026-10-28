package com.cobre.notifications.notifications.application.port.out;

import java.util.Optional;

import com.cobre.notifications.notifications.domain.Delivery;
import com.cobre.notifications.notifications.domain.DeliveryAttempt;

/**
 * Persistence of the delivery state. Both writes are conditional on the version the caller read, so two consumers
 * handling the same notification event cannot both apply their result.
 */
public interface DeliveryStore {

	Optional<Delivery> find(String notificationEventId);

	/**
	 * Stores the attempt and the resulting state in one transaction, and counts the attempt on the notification event.
	 *
	 * @return {@code false} if the stored version or state no longer matches {@code before}; nothing was written
	 */
	boolean recordAttempt(Delivery before, DeliveryAttempt attempt, Delivery after);

	/**
	 * Stores a state change that is not an attempt (retries exhausted).
	 *
	 * @return {@code false} if the stored version or state no longer matches {@code before}; nothing was written
	 */
	boolean updateState(Delivery before, Delivery after);

}
