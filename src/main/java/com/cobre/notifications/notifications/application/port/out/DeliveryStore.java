package com.cobre.notifications.notifications.application.port.out;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import com.cobre.notifications.notifications.domain.Delivery;
import com.cobre.notifications.notifications.domain.DeliveryAttempt;
import com.cobre.notifications.notifications.domain.Lease;

/**
 * Persistence of the delivery state. A worker first claims the notification event and receives a {@link Lease}; both
 * writes are conditional on the generation token of that lease (and on the version the caller read), so a stale or
 * duplicate worker cannot apply its result over another worker's.
 */
public interface DeliveryStore {

	Optional<Delivery> find(String notificationEventId);

	/**
	 * Atomically takes the lease of the notification event for its current round, only if it is still open, unchanged
	 * since {@code observed} was read, and its lease is free or expired. The generation token is increased. If the
	 * previous lease had expired, the abandoned attempt is first appended to the history as interrupted (see
	 * {@link DeliveryAttempt#INTERRUPTED}); callers must read the delivery again after a successful claim.
	 *
	 * @return the lease, or empty if the notification event is held by another worker or no longer matches
	 */
	Optional<Lease> claim(Delivery observed, String owner, Instant now, Duration leaseDuration);

	/**
	 * Reads the {@code active} flag of the subscription of this notification event from the database, at call time.
	 * It is never taken from the dispatch message.
	 */
	boolean isSubscriptionActive(String notificationEventId);

	/**
	 * Stores the attempt and the resulting state in one transaction, counts the attempt on the notification event and
	 * releases the lease.
	 *
	 * @return {@code false} if the generation token, the stored version or the state no longer matches; nothing was
	 * written
	 */
	boolean recordAttempt(Lease lease, Delivery before, DeliveryAttempt attempt, Delivery after);

	/**
	 * Stores a state change that is not an attempt (retries exhausted) and releases the lease.
	 *
	 * @return {@code false} if the generation token, the stored version or the state no longer matches; nothing was
	 * written
	 */
	boolean updateState(Lease lease, Delivery before, Delivery after);

}
