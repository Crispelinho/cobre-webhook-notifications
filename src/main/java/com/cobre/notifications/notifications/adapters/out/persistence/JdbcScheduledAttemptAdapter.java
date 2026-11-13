package com.cobre.notifications.notifications.adapters.out.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.cobre.notifications.notifications.application.port.out.ScheduledAttemptPort;

/**
 * One statement claims and enqueues: {@code FOR UPDATE SKIP LOCKED} keeps two schedulers off the same row, and the
 * due check uses the database {@code now()}, the same clock that wrote {@code next_attempt_at}. The claim clears it, so a row that was claimed (and committed) no longer matches the due filter.
 * The outbox row and the cleared time commit together, so a crash enqueues the row either completely or not at all.
 */
@Repository
class JdbcScheduledAttemptAdapter implements ScheduledAttemptPort {

	private final JdbcClient jdbc;

	JdbcScheduledAttemptAdapter(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public int enqueueDue(int limit) {
		return jdbc.sql("""
				WITH due AS (
				    SELECT id FROM notifications
				    WHERE next_attempt_at IS NOT NULL AND next_attempt_at <= now()
				      AND delivery_status IN ('pending', 'retrying')
				    ORDER BY next_attempt_at
				    LIMIT :limit
				    FOR UPDATE SKIP LOCKED
				), claimed AS (
				    UPDATE notifications n SET next_attempt_at = NULL
				    FROM due WHERE n.id = due.id
				    RETURNING n.id, n.round
				)
				INSERT INTO outbox (notification_id, round, payload, created_at)
				SELECT id, round,
				       jsonb_build_object('notification_event_id', id, 'round', round),
				       now()
				FROM claimed
				""")
			.param("limit", limit)
			.update();
	}

}
