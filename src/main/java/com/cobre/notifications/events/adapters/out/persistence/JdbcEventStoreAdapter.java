package com.cobre.notifications.events.adapters.out.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.cobre.notifications.events.application.DuplicateEventException;
import com.cobre.notifications.events.application.port.out.ActiveSubscriptionsPort;
import com.cobre.notifications.events.application.port.out.EventStorePort;
import com.cobre.notifications.events.domain.Event;
import com.cobre.notifications.events.domain.PendingNotification;
import com.cobre.notifications.events.domain.Subscription;

/**
 * Explicit SQL for ingestion. The primary key of {@code events} is the duplicate guard, so concurrent retries of the
 * same event cannot both win.
 */
@Repository
class JdbcEventStoreAdapter implements EventStorePort, ActiveSubscriptionsPort {

	private final JdbcClient jdbc;

	JdbcEventStoreAdapter(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	@Transactional(readOnly = true)
	public List<Subscription> findActiveByClientId(String clientId) {
		return jdbc
			.sql("SELECT id, client_id, url, event_types, active FROM subscriptions "
					+ "WHERE client_id = :clientId AND active")
			.param("clientId", clientId)
			.query((rs, rowNum) -> new Subscription(rs.getString("id"), rs.getString("client_id"),
					rs.getString("url"), Set.copyOf(Arrays.asList((String[]) rs.getArray("event_types").getArray())),
					rs.getBoolean("active")))
			.list();
	}

	@Override
	@Transactional
	public void store(Event event, List<PendingNotification> notifications, Instant outboxCreatedAt) {
		try {
			jdbc.sql("INSERT INTO events (id, client_id, event_type, content, created_at) "
					+ "VALUES (:id, :clientId, :eventType, :content, :createdAt)")
				.param("id", event.id())
				.param("clientId", event.clientId())
				.param("eventType", event.eventType())
				.param("content", event.content())
				.param("createdAt", Timestamp.from(event.createdAt()))
				.update();
		}
		catch (DuplicateKeyException ex) {
			throw new DuplicateEventException(event.id());
		}
		for (PendingNotification notification : notifications) {
			jdbc.sql("INSERT INTO notifications (id, event_id, subscription_id, client_id, destination_url, "
					+ "delivery_status, round, attempts, created_at) "
					+ "VALUES (:id, :eventId, :subscriptionId, :clientId, :url, 'pending', :round, 0, :createdAt)")
				.param("id", notification.id())
				.param("eventId", notification.eventId())
				.param("subscriptionId", notification.subscriptionId())
				.param("clientId", notification.clientId())
				.param("url", notification.destinationUrl())
				.param("round", notification.round())
				.param("createdAt", Timestamp.from(event.createdAt()))
				.update();
			jdbc.sql("INSERT INTO outbox (notification_id, round, payload, created_at) "
					+ "VALUES (:id, :round, CAST(:payload AS jsonb), :createdAt)")
				.param("id", notification.id())
				.param("round", notification.round())
				.param("payload", payload(notification))
				.param("createdAt", Timestamp.from(outboxCreatedAt))
				.update();
		}
	}

	/**
	 * Only the notification event id and the round travel through Kafka. The id is a generated UUID, so it needs no
	 * JSON escaping.
	 */
	private static String payload(PendingNotification notification) {
		return "{\"notification_event_id\":\"%s\",\"round\":%d}".formatted(notification.id(), notification.round());
	}

}
