package com.cobre.notifications.notifications.adapters.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import com.cobre.notifications.notifications.application.port.out.ReplayStore;
import com.cobre.notifications.kernel.domain.ClientId;
import com.cobre.notifications.notifications.domain.DeliveryStatus;
import com.cobre.notifications.notifications.domain.NotificationEvent;
import com.cobre.notifications.notifications.domain.NotificationEventId;

/**
 * Explicit SQL for replays. The state change is a conditional {@code UPDATE} on {@code version}, the round and the
 * {@code failed} status: of two concurrent replays the second one waits for the row lock, re-evaluates the condition
 * against the committed row and updates zero rows. The outbox row is inserted in the same transaction, so a new round
 * without a dispatch message (or the other way round) cannot exist.
 */
@Repository
class JdbcReplayStoreAdapter implements ReplayStore {

	private final JdbcClient jdbc;

	private final TransactionTemplate transactions;

	JdbcReplayStoreAdapter(JdbcClient jdbc, TransactionTemplate transactions) {
		this.jdbc = jdbc;
		this.transactions = transactions;
	}

	@Override
	public Optional<NotificationEvent> findById(ClientId clientId, NotificationEventId id) {
		return jdbc.sql("""
				SELECT n.id, n.event_id, n.client_id, e.event_type, e.content, n.delivery_status,
				       n.created_at, n.delivery_date, n.delivered_at, n.last_error, n.round, n.version
				FROM notifications n
				JOIN events e ON e.id = n.event_id
				WHERE n.client_id = :clientId AND n.id = :id
				""")
			.param("clientId", clientId.value())
			.param("id", id.value())
			.query((rs, rowNum) -> new NotificationEvent(new NotificationEventId(rs.getString("id")),
					rs.getString("event_id"), new ClientId(rs.getString("client_id")), rs.getString("event_type"),
					rs.getString("content"), DeliveryStatus.fromWireValue(rs.getString("delivery_status")),
					instant(rs, "created_at"), instant(rs, "delivery_date"), instant(rs, "delivered_at"),
					rs.getString("last_error"), rs.getInt("round"), rs.getLong("version"), List.of()))
			.optional();
	}

	@Override
	public boolean isSubscriptionActive(ClientId clientId, NotificationEventId id) {
		return jdbc.sql("""
				SELECT s.active FROM notifications n JOIN subscriptions s ON s.id = n.subscription_id
				WHERE n.client_id = :clientId AND n.id = :id
				""")
			.param("clientId", clientId.value())
			.param("id", id.value())
			.query(Boolean.class)
			.optional()
			.orElse(false);
	}

	@Override
	public boolean startReplay(NotificationEvent before, NotificationEvent after, Instant now) {
		return Boolean.TRUE.equals(transactions.execute(status -> {
			int updated = jdbc.sql("""
					UPDATE notifications
					SET delivery_status = :status, round = :newRound, delivery_date = NULL, last_error = NULL,
					    version = version + 1
					WHERE id = :id AND client_id = :clientId AND version = :version AND round = :round
					  AND delivery_status = 'failed'
					  AND EXISTS (SELECT 1 FROM subscriptions s WHERE s.id = notifications.subscription_id AND s.active)
					""")
				.param("status", after.deliveryStatus().wireValue())
				.param("newRound", after.round())
				.param("id", before.id().value())
				.param("clientId", before.clientId().value())
				.param("version", before.version())
				.param("round", before.round())
				.update();
			if (updated != 1) {
				return false;
			}
			// The database builds the JSON, so an id with quotes or backslashes cannot break it.
			jdbc.sql("INSERT INTO outbox (notification_id, round, payload, created_at) "
					+ "VALUES (:id, :round, jsonb_build_object('notification_event_id', CAST(:id AS text), "
					+ "'round', CAST(:round AS integer)), :createdAt)")
				.param("id", after.id().value())
				.param("round", after.round())
				.param("createdAt", Timestamp.from(now))
				.update();
			return true;
		}));
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
		return value == null ? null : value.toInstant();
	}

}
