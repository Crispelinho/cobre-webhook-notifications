package com.cobre.notifications.notifications.adapters.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.cobre.notifications.notifications.application.Page;
import com.cobre.notifications.notifications.application.PageQuery;
import com.cobre.notifications.notifications.application.port.out.NotificationQueryPort;
import com.cobre.notifications.kernel.domain.ClientId;
import com.cobre.notifications.notifications.domain.DeliveryAttempt;
import com.cobre.notifications.notifications.domain.DeliveryStatus;
import com.cobre.notifications.notifications.domain.NotificationEvent;
import com.cobre.notifications.notifications.domain.NotificationEventId;
import com.cobre.notifications.notifications.domain.NotificationFilter;

/**
 * Read side of notification events, with explicit SQL. Every statement filters on {@code client_id}.
 */
@Repository
@Transactional(readOnly = true)
class JdbcNotificationQueryAdapter implements NotificationQueryPort {

	private static final String SELECT_NOTIFICATION = """
			SELECT n.id, n.event_id, n.client_id, e.event_type, e.content, n.delivery_status,
			       n.created_at, n.delivery_date, n.delivered_at, n.last_error, n.round, n.version
			FROM notifications n
			JOIN events e ON e.id = n.event_id
			""";

	private final JdbcClient jdbc;

	JdbcNotificationQueryAdapter(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Page<NotificationEvent> findPage(ClientId clientId, NotificationFilter filter, PageQuery pageQuery) {
		Map<String, Object> params = new HashMap<>();
		String where = whereClause(clientId, filter, params);

		long total = jdbc.sql("SELECT count(*) FROM notifications n" + where)
			.params(params)
			.query(Long.class)
			.single();

		params.put("limit", pageQuery.size());
		params.put("offset", (long) pageQuery.page() * pageQuery.size());
		List<NotificationEvent> items = jdbc
			.sql(SELECT_NOTIFICATION + where + " ORDER BY n.created_at DESC, n.id DESC LIMIT :limit OFFSET :offset")
			.params(params)
			// The list does not expose delivery attempts; they are loaded only for the detail view.
			.query((rs, rowNum) -> toNotificationEvent(rs, List.of()))
			.list();

		return new Page<>(items, pageQuery.page(), pageQuery.size(), total);
	}

	@Override
	public Optional<NotificationEvent> findById(ClientId clientId, NotificationEventId id) {
		List<DeliveryAttempt> attempts = jdbc.sql("""
				SELECT a.round, a.attempt_number, a.attempted_at, a.http_status, a.error_type, a.latency_ms
				FROM delivery_attempts a
				JOIN notifications n ON n.id = a.notification_id
				WHERE n.client_id = :clientId AND n.id = :id
				ORDER BY a.round, a.attempt_number
				""")
			.param("clientId", clientId.value())
			.param("id", id.value())
			.query((rs, rowNum) -> new DeliveryAttempt(rs.getInt("round"), rs.getInt("attempt_number"),
					instant(rs, "attempted_at"), rs.getObject("http_status", Integer.class),
					rs.getString("error_type"), rs.getObject("latency_ms", Integer.class)))
			.list();

		return jdbc.sql(SELECT_NOTIFICATION + " WHERE n.client_id = :clientId AND n.id = :id")
			.param("clientId", clientId.value())
			.param("id", id.value())
			.query((rs, rowNum) -> toNotificationEvent(rs, attempts))
			.optional();
	}

	private static String whereClause(ClientId clientId, NotificationFilter filter, Map<String, Object> params) {
		List<String> conditions = new ArrayList<>();
		conditions.add("n.client_id = :clientId");
		params.put("clientId", clientId.value());
		if (filter.createdFrom() != null) {
			conditions.add("n.created_at >= :createdFrom");
			params.put("createdFrom", OffsetDateTime.ofInstant(filter.createdFrom(), ZoneOffset.UTC));
		}
		if (filter.createdTo() != null) {
			conditions.add("n.created_at < :createdTo");
			params.put("createdTo", OffsetDateTime.ofInstant(filter.createdTo(), ZoneOffset.UTC));
		}
		if (filter.deliveryStatus() != null) {
			conditions.add("n.delivery_status = :deliveryStatus");
			params.put("deliveryStatus", filter.deliveryStatus().wireValue());
		}
		return " WHERE " + String.join(" AND ", conditions);
	}

	private static NotificationEvent toNotificationEvent(ResultSet rs, List<DeliveryAttempt> attempts)
			throws SQLException {
		return new NotificationEvent(new NotificationEventId(rs.getString("id")), rs.getString("event_id"),
				new ClientId(rs.getString("client_id")), rs.getString("event_type"), rs.getString("content"),
				DeliveryStatus.fromWireValue(rs.getString("delivery_status")), instant(rs, "created_at"),
				instant(rs, "delivery_date"), instant(rs, "delivered_at"), rs.getString("last_error"), rs.getInt("round"),
				rs.getLong("version"), attempts);
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
		return value == null ? null : value.toInstant();
	}

}
