package com.cobre.notifications.notifications.application.port.out;

import java.time.Duration;

import com.cobre.notifications.notifications.domain.DeliveryOutcome;
import com.cobre.notifications.notifications.domain.DeliveryStatus;

/**
 * Observation points of the delivery path. Implementations must never throw and never receive event content,
 * destinations or secrets: only outcomes, statuses and durations.
 */
public interface DeliveryMetrics {

	/**
	 * Does nothing; for code and tests that do not observe metrics.
	 */
	DeliveryMetrics NONE = new DeliveryMetrics() {
		@Override
		public void attemptRecorded(DeliveryOutcome outcome, long latencyMs) {
		}

		@Override
		public void finalOutcome(DeliveryStatus status, Duration sinceCreation) {
		}

		@Override
		public void deadLettered() {
		}
	};

	/**
	 * One attempt was recorded (not counted when a concurrent consumer won the race).
	 */
	void attemptRecorded(DeliveryOutcome outcome, long latencyMs);

	/**
	 * A notification event reached a final status for its round.
	 *
	 * @param sinceCreation time from creation of the notification event to its final outcome
	 */
	void finalOutcome(DeliveryStatus status, Duration sinceCreation);

	/**
	 * A message reached the dead-letter topic (readable or not).
	 */
	void deadLettered();

}
