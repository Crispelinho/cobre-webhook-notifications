package com.cobre.notifications.notifications.application;

import java.util.Objects;

import com.cobre.notifications.notifications.application.port.out.ScheduledAttemptPort;

/**
 * Turns the attempts a receiver asked to delay (Retry-After) into dispatch messages once they are due. The normal
 * delivery path then sends them, so the limit of attempts per round is enforced in one place.
 */
public class EnqueueDueAttempts {

	private final ScheduledAttemptPort scheduledAttempts;

	private final int batchSize;

	public EnqueueDueAttempts(ScheduledAttemptPort scheduledAttempts, int batchSize) {
		if (batchSize < 1) {
			throw new IllegalArgumentException("batchSize must be at least 1");
		}
		this.scheduledAttempts = Objects.requireNonNull(scheduledAttempts);
		this.batchSize = batchSize;
	}

	/**
	 * @return the number of dispatch messages enqueued in this poll
	 */
	public int enqueueDue() {
		return scheduledAttempts.enqueueDue(batchSize);
	}

}
