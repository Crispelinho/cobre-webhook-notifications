package com.cobre.notifications.subscriptions.adapters.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.cobre.notifications.subscriptions.application.SubscriptionChanges;
import com.cobre.notifications.subscriptions.application.port.out.SubscriptionRepository;
import com.cobre.notifications.subscriptions.domain.ClientId;
import com.cobre.notifications.subscriptions.domain.EventTypes;
import com.cobre.notifications.subscriptions.domain.Subscription;
import com.cobre.notifications.subscriptions.domain.SubscriptionId;
import com.cobre.notifications.subscriptions.domain.WebhookUrl;

/**
 * Subscriptions in the {@code subscriptions} table, with explicit SQL. Every statement filters on {@code client_id},
 * and no query reads {@code secret_ciphertext}.
 */
@Repository
@Transactional
class JdbcSubscriptionRepository implements SubscriptionRepository {

	private static final String COLUMNS = "id, client_id, url, event_types, active, created_at";

	private final JdbcClient jdbc;

	JdbcSubscriptionRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public void insert(Subscription subscription, byte[] secretCiphertext) {
		jdbc.sql("""
				INSERT INTO subscriptions (id, client_id, url, event_types, active, secret_ciphertext, created_at)
				VALUES (:id, :clientId, :url, :eventTypes, :active, :secretCiphertext, :createdAt)
				""")
			.param("id", subscription.id().value())
			.param("clientId", subscription.clientId().value())
			.param("url", subscription.url().value())
			.param("eventTypes", eventTypesArray(subscription.eventTypes()))
			.param("active", subscription.active())
			.param("secretCiphertext", secretCiphertext)
			.param("createdAt", OffsetDateTime.ofInstant(subscription.createdAt(), ZoneOffset.UTC))
			.update();
	}

	@Override
	@Transactional(readOnly = true)
	public List<Subscription> findAll(ClientId clientId, int limit) {
		return jdbc.sql("SELECT " + COLUMNS
				+ " FROM subscriptions WHERE client_id = :clientId ORDER BY created_at DESC, id DESC LIMIT :limit")
			.param("clientId", clientId.value())
			.param("limit", limit)
			.query((rs, rowNum) -> toSubscription(rs))
			.list();
	}

	@Override
	@Transactional(readOnly = true)
	public long countActive(ClientId clientId) {
		// Served by idx_subscriptions_client_active.
		return jdbc.sql("SELECT count(*) FROM subscriptions WHERE client_id = :clientId AND active")
			.param("clientId", clientId.value())
			.query(Long.class)
			.single();
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<Subscription> findById(ClientId clientId, SubscriptionId id) {
		return jdbc.sql("SELECT " + COLUMNS + " FROM subscriptions WHERE client_id = :clientId AND id = :id")
			.param("clientId", clientId.value())
			.param("id", id.value())
			.query((rs, rowNum) -> toSubscription(rs))
			.optional();
	}

	@Override
	public Optional<Subscription> update(ClientId clientId, SubscriptionId id, SubscriptionChanges changes) {
		// One statement, so two concurrent updates cannot overwrite each other's fields with stale values.
		return jdbc.sql("""
				UPDATE subscriptions
				SET active = COALESCE(CAST(:active AS boolean), active),
				    event_types = COALESCE(CAST(:eventTypes AS text[]), event_types)
				WHERE client_id = :clientId AND id = :id
				RETURNING\s""" + COLUMNS)
			.param("active", changes.active())
			.param("eventTypes", changes.eventTypes() == null ? null : eventTypesArray(changes.eventTypes()))
			.param("clientId", clientId.value())
			.param("id", id.value())
			.query((rs, rowNum) -> toSubscription(rs))
			.optional();
	}

	private static String[] eventTypesArray(EventTypes eventTypes) {
		return eventTypes.values().toArray(String[]::new);
	}

	private static Subscription toSubscription(ResultSet rs) throws SQLException {
		String[] eventTypes = (String[]) rs.getArray("event_types").getArray();
		return new Subscription(new SubscriptionId(rs.getString("id")), new ClientId(rs.getString("client_id")),
				new WebhookUrl(rs.getString("url")), new EventTypes(List.of(eventTypes)), rs.getBoolean("active"),
				rs.getObject("created_at", OffsetDateTime.class).toInstant());
	}

}
