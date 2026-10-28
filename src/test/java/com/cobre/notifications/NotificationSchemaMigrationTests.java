package com.cobre.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@Transactional
class NotificationSchemaMigrationTests {

	@Autowired
	private JdbcTemplate jdbc;

	@Test
	void migrationCreatesNotificationTables() {
		var tables = jdbc.queryForList("""
				SELECT table_name FROM information_schema.tables
				WHERE table_schema = 'public'
				""", String.class);

		assertThat(tables).contains("events", "subscriptions", "notifications", "delivery_attempts");
	}

	@Test
	void deliveryStatusOutsideTheStateMachineIsRejected() {
		insertEventAndSubscription();

		assertThatThrownBy(() -> insertNotification("N1", "delivered"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void sameAttemptCannotBeRecordedTwice() {
		insertEventAndSubscription();
		insertNotification("N1", "pending");
		String insertAttempt = """
				INSERT INTO delivery_attempts (notification_id, round, attempt_number, attempted_at)
				VALUES ('N1', 1, 1, now())
				""";
		jdbc.update(insertAttempt);

		assertThatThrownBy(() -> jdbc.update(insertAttempt))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	private void insertEventAndSubscription() {
		jdbc.update("""
				INSERT INTO events (id, client_id, event_type, content, created_at)
				VALUES ('E1', 'CLIENT001', 'credit_transfer', 'test', now())
				""");
		jdbc.update("""
				INSERT INTO subscriptions (id, client_id, url, event_types, created_at)
				VALUES ('S1', 'CLIENT001', 'https://webhook.example.com/CLIENT001', ARRAY['credit_transfer'], now())
				""");
	}

	private void insertNotification(String id, String status) {
		jdbc.update("""
				INSERT INTO notifications (id, event_id, subscription_id, client_id, destination_url,
				                           delivery_status, created_at)
				VALUES (?, 'E1', 'S1', 'CLIENT001', 'https://webhook.example.com/CLIENT001', ?, now())
				""", id, status);
	}

}
