package com.cobre.notifications.notifications.application;

import java.time.Clock;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cobre.notifications.notifications.application.port.out.LeaseRecoveryStore;
import com.cobre.notifications.notifications.application.port.out.LeaseRecoveryStore.Recovered;

/**
 * Finds notification events whose lease expired without an outcome (the worker crashed, was redeployed or lost its
 * partition) and puts them back in flow. The interrupted attempt is kept in the history as {@code interrupted}: the
 * send may or may not have reached the receiver, so it is never recorded as a receiver failure. The next claim sends
 * again with the same {@code Cobre-Notification-Id}, which the receiver uses to deduplicate (at-least-once delivery).
 * Logs carry ids and rounds only.
 */
public class RecoverInterruptedDeliveries {

	private static final Logger log = LoggerFactory.getLogger(RecoverInterruptedDeliveries.class);

	private final LeaseRecoveryStore store;

	private final Clock clock;

	private final int maxAttempts;

	private final int batchSize;

	public RecoverInterruptedDeliveries(LeaseRecoveryStore store, Clock clock, int maxAttempts, int batchSize) {
		if (maxAttempts < 1 || batchSize < 1) {
			throw new IllegalArgumentException("maxAttempts and batchSize must be at least 1");
		}
		this.store = Objects.requireNonNull(store);
		this.clock = Objects.requireNonNull(clock);
		this.maxAttempts = maxAttempts;
		this.batchSize = batchSize;
	}

	/**
	 * @return how many notification events were released
	 */
	public int recover() {
		List<Recovered> recovered = store.recoverExpired(clock.instant(), maxAttempts, batchSize);
		for (Recovered item : recovered) {
			if (item.failed()) {
				log.error("Notification event {} round {} failed: interrupted on its last attempt",
						item.notificationEventId(), item.round());
			}
			else {
				log.warn("Notification event {} round {} had an expired lease and was dispatched again",
						item.notificationEventId(), item.round());
			}
		}
		return recovered.size();
	}

}
