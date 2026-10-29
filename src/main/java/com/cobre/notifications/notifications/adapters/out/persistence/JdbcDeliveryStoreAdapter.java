package com.cobre.notifications.notifications.adapters.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import com.cobre.notifications.notifications.application.port.out.DeliveryStore;
import com.cobre.notifications.notifications.application.port.out.LeaseRecoveryStore;
import com.cobre.notifications.notifications.domain.Delivery;
import com.cobre.notifications.notifications.domain.DeliveryAttempt;
import com.cobre.notifications.notifications.domain.DeliveryStatus;
import com.cobre.notifications.notifications.domain.Lease;
import com.cobre.notifications.notifications.domain.NotificationEventId;

/**
 * Explicit SQL for the delivery side. A worker takes a lease with a conditional claim that increases the generation
 * token; every result write is a conditional {@code UPDATE} on that token, {@code version}, the round and an open
 * status: a stale, duplicate or concurrent worker updates zero rows and its attempt row is rolled back. The unique
 * constraint on {@code (notification_id, round, attempt_number)} is the second line of defense.
 *
 * <p>
 * An interrupted attempt (lease expired without a result) is appended to the history with {@code error_type}
 * {@code interrupted} and counted in the round, but it is not assumed to have failed at the receiver: the next claim
 * sends again with the same {@code Cobre-Notification-Id} and the receiver deduplicates. Recorded attempts are never
 * updated or deleted.
 */
@Repository
class JdbcDeliveryStoreAdapter implements DeliveryStore, LeaseRecoveryStore {

	private final JdbcClient jdbc;

	private final TransactionTemplate transactions;

	JdbcDeliveryStoreAdapter(JdbcClient jdbc, TransactionTemplate transactions) {
		this.jdbc = jdbc;
		this.transactions = transactions;
	}

	@Override
	public Optional<Delivery> find(String notificationEventId) {
		return jdbc.sql("""
				SELECT n.id, n.event_id, e.event_type, e.content, n.destination_url, n.created_at,
				       n.delivery_status, n.round, n.delivery_date, n.delivered_at, n.last_error, n.version,
				       n.subscription_id, s.secret_ciphertext,
				       (SELECT count(*) FROM delivery_attempts a
				         WHERE a.notification_id = n.id AND a.round = n.round) AS attempts_in_round
				FROM notifications n
				JOIN events e ON e.id = n.event_id
				JOIN subscriptions s ON s.id = n.subscription_id
				WHERE n.id = :id
				""")
			.param("id", notificationEventId)
			.query((rs, rowNum) -> toDelivery(rs))
			.optional();
	}

	@Override
	public Optional<Lease> claim(Delivery observed, String owner, Instant now, Duration leaseDuration) {
		Optional<Lease> claimed = transactions.execute(status -> {
			// The row lock serializes concurrent claims: the loser re-reads the winner's unexpired lease.
			Optional<Held> held = jdbc.sql("""
					SELECT lease_expires_at, generation FROM notifications
					WHERE id = :id AND round = :round AND version = :version
					  AND delivery_status IN ('pending', 'retrying')
					FOR UPDATE
					""")
				.param("id", observed.id().value())
				.param("round", observed.round())
				.param("version", observed.version())
				.query((rs, rowNum) -> new Held(instant(rs, "lease_expires_at"), rs.getLong("generation")))
				.optional();
			if (held.isEmpty()) {
				return Optional.<Lease>empty();
			}
			Instant expiresAt = held.get().expiresAt();
			if (expiresAt != null) {
				if (expiresAt.isAfter(now)) {
					return Optional.<Lease>empty();
				}
				// The previous holder never recorded a result: keep its attempt in the history.
				recordInterruption(observed.id().value(), observed.round(), now);
			}
			long generation = held.get().generation() + 1;
			jdbc.sql("""
					UPDATE notifications
					SET lease_owner = :owner, lease_expires_at = :expiresAt, generation = :generation
					WHERE id = :id
					""")
				.param("owner", owner)
				.param("expiresAt", Timestamp.from(now.plus(leaseDuration)))
				.param("generation", generation)
				.param("id", observed.id().value())
				.update();
			return Optional.of(new Lease(owner, generation));
		});
		return claimed == null ? Optional.empty() : claimed;
	}

	@Override
	public List<Recovered> recoverExpired(Instant now, int maxAttempts, int batchSize) {
		List<Recovered> recovered = transactions.execute(status -> {
			List<Expired> expired = jdbc.sql("""
					SELECT id, round FROM notifications
					WHERE lease_expires_at <= :now AND delivery_status IN ('pending', 'retrying')
					ORDER BY lease_expires_at
					LIMIT :limit
					FOR UPDATE SKIP LOCKED
					""")
				.param("now", Timestamp.from(now))
				.param("limit", batchSize)
				.query((rs, rowNum) -> new Expired(rs.getString("id"), rs.getInt("round")))
				.list();
			List<Recovered> released = new ArrayList<>();
			for (Expired row : expired) {
				int attempts = recordInterruption(row.id(), row.round(), now);
				boolean failed = attempts >= maxAttempts;
				// Releasing also increases the generation, so the stale worker is fenced before anyone reclaims.
				jdbc.sql("""
						UPDATE notifications
						SET lease_owner = NULL, lease_expires_at = NULL, generation = generation + 1,
						    delivery_status = CASE WHEN :failed THEN 'failed' ELSE delivery_status END,
						    delivery_date = CASE WHEN :failed THEN CAST(:now AS timestamptz) ELSE delivery_date END
						WHERE id = :id
						""")
					.param("failed", failed)
					.param("now", Timestamp.from(now))
					.param("id", row.id())
					.update();
				if (!failed) {
					// Same transaction as the release: a released event without a dispatch message cannot exist.
					jdbc.sql("INSERT INTO outbox (notification_id, round, payload, created_at) "
							+ "VALUES (:id, :round, jsonb_build_object('notification_event_id', CAST(:id AS text), "
							+ "'round', CAST(:round AS integer)), :createdAt)")
						.param("id", row.id())
						.param("round", row.round())
						.param("createdAt", Timestamp.from(now))
						.update();
				}
				released.add(new Recovered(row.id(), row.round(), failed));
			}
			return released;
		});
		return recovered == null ? List.of() : recovered;
	}

	/**
	 * Appends the abandoned attempt and counts it in the round. The caller holds the row lock. Returns the attempts of
	 * the round after the append.
	 */
	private int recordInterruption(String id, int round, Instant now) {
		long recorded = jdbc.sql("SELECT count(*) FROM delivery_attempts WHERE notification_id = :id AND round = :round")
			.param("id", id)
			.param("round", round)
			.query(Long.class)
			.single();
		int number = (int) recorded + 1;
		jdbc.sql("INSERT INTO delivery_attempts (notification_id, round, attempt_number, attempted_at, error_type) "
				+ "VALUES (:id, :round, :number, :attemptedAt, :errorType)")
			.param("id", id)
			.param("round", round)
			.param("number", number)
			.param("attemptedAt", Timestamp.from(now))
			.param("errorType", DeliveryAttempt.INTERRUPTED)
			.update();
		jdbc.sql("""
				UPDATE notifications
				SET delivery_status = 'retrying', attempts = attempts + 1, last_error = :errorType,
				    version = version + 1
				WHERE id = :id
				""")
			.param("errorType", DeliveryAttempt.INTERRUPTED)
			.param("id", id)
			.update();
		return number;
	}

	@Override
	public boolean isSubscriptionActive(String notificationEventId) {
		return jdbc.sql("""
				SELECT s.active FROM notifications n JOIN subscriptions s ON s.id = n.subscription_id
				WHERE n.id = :id
				""")
			.param("id", notificationEventId)
			.query(Boolean.class)
			.optional()
			.orElse(false);
	}

	@Override
	public boolean recordAttempt(Lease lease, Delivery before, DeliveryAttempt attempt, Delivery after) {
		return Boolean.TRUE.equals(transactions.execute(status -> {
			if (!applyState(lease, before, after, 1)) {
				return false;
			}
			try {
				jdbc.sql("INSERT INTO delivery_attempts (notification_id, round, attempt_number, attempted_at, "
						+ "http_status, error_type, latency_ms) "
						+ "VALUES (:id, :round, :number, :attemptedAt, :httpStatus, :errorType, :latencyMs)")
					.param("id", before.id().value())
					.param("round", attempt.round())
					.param("number", attempt.attemptNumber())
					.param("attemptedAt", Timestamp.from(attempt.attemptedAt()))
					.param("httpStatus", attempt.httpStatus())
					.param("errorType", attempt.errorType())
					.param("latencyMs", attempt.latencyMs())
					.update();
			}
			catch (DuplicateKeyException ex) {
				// The same attempt was recorded already: undo the state change that went with it.
				status.setRollbackOnly();
				return false;
			}
			return true;
		}));
	}

	@Override
	public boolean updateState(Lease lease, Delivery before, Delivery after) {
		return applyState(lease, before, after, 0);
	}

	private boolean applyState(Lease lease, Delivery before, Delivery after, int attemptsAdded) {
		return jdbc.sql("""
				UPDATE notifications
				SET delivery_status = :status, attempts = attempts + :attemptsAdded, delivery_date = :deliveryDate,
				    delivered_at = :deliveredAt, last_error = :lastError, version = version + 1,
				    lease_owner = NULL, lease_expires_at = NULL
				WHERE id = :id AND generation = :generation AND version = :version AND round = :round
				  AND delivery_status IN ('pending', 'retrying')
				""")
			.param("generation", lease.generation())
			.param("status", after.status().wireValue())
			.param("attemptsAdded", attemptsAdded)
			.param("deliveryDate", timestamp(after.deliveryDate()))
			.param("deliveredAt", timestamp(after.deliveredAt()))
			.param("lastError", after.lastError())
			.param("id", before.id().value())
			.param("version", before.version())
			.param("round", before.round())
			.update() == 1;
	}

	private record Held(Instant expiresAt, long generation) {
	}

	private record Expired(String id, int round) {
	}

	private static Delivery toDelivery(ResultSet rs) throws SQLException {
		return new Delivery(new NotificationEventId(rs.getString("id")), rs.getString("event_id"),
				rs.getString("event_type"), rs.getString("content"), rs.getString("destination_url"),
				instant(rs, "created_at"), DeliveryStatus.fromWireValue(rs.getString("delivery_status")),
				rs.getInt("round"), rs.getInt("attempts_in_round"), instant(rs, "delivery_date"),
				instant(rs, "delivered_at"), rs.getString("last_error"), rs.getLong("version"),
				rs.getString("subscription_id"), rs.getBytes("secret_ciphertext"));
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
		return value == null ? null : value.toInstant();
	}

	private static Timestamp timestamp(Instant value) {
		return value == null ? null : Timestamp.from(value);
	}

}
