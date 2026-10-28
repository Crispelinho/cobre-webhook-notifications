package com.cobre.notifications.notifications.adapters.out.persistence;

import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import com.cobre.notifications.notifications.application.DispatchMessage;
import com.cobre.notifications.notifications.application.port.out.OutboxPort;

/**
 * Explicit SQL for the relay. {@code FOR UPDATE SKIP LOCKED} lets several relay instances share the outbox without
 * publishing the same row twice at the same time. The transaction spans the broker acknowledgements of one bounded
 * batch; if the process dies before the commit, the rows are published again (at-least-once, the consumer is
 * idempotent).
 */
@Repository
class JdbcOutboxAdapter implements OutboxPort {

	private final JdbcClient jdbc;

	private final TransactionTemplate transactions;

	JdbcOutboxAdapter(JdbcClient jdbc, TransactionTemplate transactions) {
		this.jdbc = jdbc;
		this.transactions = transactions;
	}

	@Override
	public int relayBatch(int limit, Handler handler) {
		Integer published = transactions.execute(status -> {
			List<Row> rows = jdbc
				.sql("SELECT id, notification_id, round FROM outbox WHERE published_at IS NULL "
						+ "ORDER BY id LIMIT :limit FOR UPDATE SKIP LOCKED")
				.param("limit", limit)
				.query((rs, rowNum) -> new Row(rs.getLong("id"),
						new DispatchMessage(rs.getString("notification_id"), rs.getInt("round"))))
				.list();
			List<Long> confirmed = new ArrayList<>();
			for (Row row : rows) {
				if (!handler.handle(row.message())) {
					break;
				}
				confirmed.add(row.id());
			}
			if (!confirmed.isEmpty()) {
				jdbc.sql("UPDATE outbox SET published_at = now() WHERE id IN (:ids)").param("ids", confirmed).update();
			}
			return confirmed.size();
		});
		return published == null ? 0 : published;
	}

	private record Row(long id, DispatchMessage message) {
	}

}
