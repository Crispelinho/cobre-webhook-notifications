package com.cobre.notifications.notifications.adapters.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import com.cobre.notifications.notifications.application.port.out.DeliveryStore;
import com.cobre.notifications.notifications.domain.Delivery;
import com.cobre.notifications.notifications.domain.DeliveryAttempt;
import com.cobre.notifications.notifications.domain.DeliveryStatus;
import com.cobre.notifications.notifications.domain.NotificationEventId;

/**
 * Explicit SQL for the delivery side. Every write is a conditional {@code UPDATE} on {@code version}, the round and an
 * open status: a duplicate or concurrent consumer updates zero rows and its attempt row is rolled back. The unique
 * constraint on {@code (notification_id, round, attempt_number)} is the second line of defense.
 */
@Repository
class JdbcDeliveryStoreAdapter implements DeliveryStore {

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
	public boolean recordAttempt(Delivery before, DeliveryAttempt attempt, Delivery after) {
		return Boolean.TRUE.equals(transactions.execute(status -> {
			if (!applyState(before, after, 1)) {
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
	public boolean updateState(Delivery before, Delivery after) {
		return applyState(before, after, 0);
	}

	private boolean applyState(Delivery before, Delivery after, int attemptsAdded) {
		return jdbc.sql("""
				UPDATE notifications
				SET delivery_status = :status, attempts = attempts + :attemptsAdded, delivery_date = :deliveryDate,
				    delivered_at = :deliveredAt, last_error = :lastError, version = version + 1
				WHERE id = :id AND version = :version AND round = :round
				  AND delivery_status IN ('pending', 'retrying')
				""")
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
