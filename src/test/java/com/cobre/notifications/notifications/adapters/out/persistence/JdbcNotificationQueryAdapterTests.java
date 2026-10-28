package com.cobre.notifications.notifications.adapters.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.jdbc.Sql;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.cobre.notifications.notifications.application.PageQuery;
import com.cobre.notifications.notifications.domain.ClientId;
import com.cobre.notifications.notifications.domain.DeliveryStatus;
import com.cobre.notifications.notifications.domain.NotificationEvent;
import com.cobre.notifications.notifications.domain.NotificationEventId;
import com.cobre.notifications.notifications.domain.NotificationFilter;

@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(JdbcNotificationQueryAdapter.class)
@Sql("/sample-notifications.sql")
class JdbcNotificationQueryAdapterTests {

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18.6"));

	private static final ClientId CLIENT_001 = new ClientId("CLIENT001");

	private static final ClientId CLIENT_002 = new ClientId("CLIENT002");

	private static final ClientId CLIENT_003 = new ClientId("CLIENT003");

	@Autowired
	private JdbcNotificationQueryAdapter adapter;

	@Test
	void listsOnlyTheClientsNotificationEventsNewestFirst() {
		var page = adapter.findPage(CLIENT_001, NotificationFilter.none(), PageQuery.firstPage());

		assertThat(ids(page.items())).containsExactly("EVT010", "EVT007", "EVT002", "EVT001");
		assertThat(page.totalElements()).isEqualTo(4);
		assertThat(page.totalPages()).isEqualTo(1);
	}

	@Test
	void filtersByDeliveryStatus() {
		var filter = new NotificationFilter(null, null, DeliveryStatus.FAILED);

		var page = adapter.findPage(CLIENT_003, filter, PageQuery.firstPage());

		assertThat(ids(page.items())).containsExactly("EVT009", "EVT005");
	}

	@Test
	void filtersByCreationDateWithInclusiveFromAndExclusiveTo() {
		var filter = new NotificationFilter(Instant.parse("2024-03-15T10:15:45Z"), Instant.parse("2024-03-15T15:20:40Z"),
				null);

		var page = adapter.findPage(CLIENT_001, filter, PageQuery.firstPage());

		assertThat(ids(page.items())).containsExactly("EVT002");
	}

	@Test
	void paginatesWithTotals() {
		var page = adapter.findPage(CLIENT_001, NotificationFilter.none(), new PageQuery(1, 3));

		assertThat(ids(page.items())).containsExactly("EVT001");
		assertThat(page.totalElements()).isEqualTo(4);
		assertThat(page.totalPages()).isEqualTo(2);
	}

	@Test
	void returnsAnEmptyPageWhenNothingMatches() {
		var filter = new NotificationFilter(null, null, DeliveryStatus.RETRYING);

		var page = adapter.findPage(CLIENT_002, filter, PageQuery.firstPage());

		assertThat(page.items()).isEmpty();
		assertThat(page.totalElements()).isZero();
	}

	@Test
	void detailIncludesEventDataAndAttemptsInOrder() {
		NotificationEvent detail = adapter.findById(CLIENT_002, new NotificationEventId("EVT003")).orElseThrow();

		assertThat(detail.eventType()).isEqualTo("credit_transfer");
		assertThat(detail.deliveryStatus()).isEqualTo(DeliveryStatus.FAILED);
		assertThat(detail.deliveredAt()).isNull();
		assertThat(detail.attempts()).extracting(a -> a.attemptNumber()).containsExactly(1, 2);
		assertThat(detail.attempts().getFirst().httpStatus()).isNull();
	}

	@Test
	void detailOfAnotherClientsNotificationEventIsNotFound() {
		assertThat(adapter.findById(CLIENT_001, new NotificationEventId("EVT003"))).isEmpty();
	}

	private static List<String> ids(List<NotificationEvent> items) {
		return items.stream().map(n -> n.id().value()).toList();
	}

}
