package com.cobre.notifications.notifications.application.port.out;

import java.time.Instant;
import java.util.List;

/**
 * Recovery of notification events whose lease expired without a recorded outcome.
 */
public interface LeaseRecoveryStore {

	/**
	 * Releases up to {@code batchSize} expired leases, skipping rows another instance is already recovering. For each
	 * one, in one transaction: the interrupted attempt is appended to the history ({@code error_type}
	 * {@code interrupted}, no HTTP status, counted in the round), the lease is cleared, the generation token is
	 * increased so the stale worker is fenced, and either a dispatch message is enqueued in the outbox or, when the
	 * round has no attempt left, the notification event becomes {@code failed}. Recorded attempts are never changed.
	 */
	List<Recovered> recoverExpired(Instant now, int maxAttempts, int batchSize);

	/**
	 * @param failed {@code true} if the interrupted attempt was the last one of the round
	 */
	record Recovered(String notificationEventId, int round, boolean failed) {
	}

}
