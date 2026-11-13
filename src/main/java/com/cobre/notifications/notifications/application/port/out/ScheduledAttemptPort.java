package com.cobre.notifications.notifications.application.port.out;

/**
 * Access to attempts that wait for a time chosen by the receiver (Retry-After).
 */
public interface ScheduledAttemptPort {

	/**
	 * Claims up to {@code limit} open notification events whose next attempt is due by the database clock: clears the stored
	 * time and writes a dispatch message to the outbox for the current round, in one atomic statement. A row another
	 * instance holds is skipped, and a row already claimed no longer matches, so a due row is enqueued exactly once.
	 *
	 * @return how many dispatch messages were enqueued
	 */
	int enqueueDue(int limit);

}
