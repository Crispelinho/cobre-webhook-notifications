package com.cobre.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.oneOf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.cobre.notifications.notifications.adapters.in.seed.SampleDataSeeder;

/**
 * One test per scenario in {@code openspec/changes/add-self-service-query/specs/self-service-api/spec.md}, run against
 * the seeded sample data.
 */
@SpringBootTest(properties = "app.seed.enabled=true")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SelfServiceApiAcceptanceTests {

	@Autowired
	private MockMvc mvc;

	@Nested
	class ListNotificationEvents {

		@Test
		void clientSeesOnlyItsOwnNotificationEvents() throws Exception {
			mvc.perform(as("CLIENT001", get("/notification_events")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[*].notification_event_id").value(contains("EVT010", "EVT007", "EVT002",
						"EVT001")));
		}

		@Test
		void clientIdentityInTheRequestIsIgnored() throws Exception {
			mvc.perform(as("CLIENT001", get("/notification_events").param("client_id", "CLIENT002")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[*].notification_event_id").value(contains("EVT010", "EVT007", "EVT002",
						"EVT001")));
		}

	}

	@Nested
	class FilterNotificationEvents {

		@Test
		void filterByDeliveryStatus() throws Exception {
			mvc.perform(as("CLIENT003", get("/notification_events").param("delivery_status", "failed")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[*].notification_event_id").value(contains("EVT009", "EVT005")));
		}

		@Test
		void filterByCreationDateRange() throws Exception {
			mvc.perform(as("CLIENT001", get("/notification_events").param("created_from", "2024-03-15T10:00:00Z")
				.param("created_to", "2024-03-15T16:00:00Z")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[*].notification_event_id").value(contains("EVT007", "EVT002")));
		}

		@Test
		void noMatchingNotificationEvents() throws Exception {
			mvc.perform(as("CLIENT002", get("/notification_events").param("delivery_status", "retrying")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data").value(empty()))
				.andExpect(jsonPath("$.total_elements").value(0));
		}

		@ParameterizedTest
		@ValueSource(strings = { "delivery_status=delivered", "delivery_status=COMPLETED", "created_from=yesterday",
				"created_to=2024-03-15", "created_from=2024-03-15T16:00:00Z&created_to=2024-03-15T10:00:00Z",
				"page=abc", "page=-1", "size=0" })
		void invalidFilterValues(String query) throws Exception {
			mvc.perform(as("CLIENT001", get("/notification_events?" + query)))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.detail").isNotEmpty());
		}

	}

	@Nested
	class PaginateNotificationEvents {

		@Test
		void defaultPage() throws Exception {
			mvc.perform(as("CLIENT001", get("/notification_events")))
				.andExpect(jsonPath("$.page").value(0))
				.andExpect(jsonPath("$.size").value(20))
				.andExpect(jsonPath("$.total_elements").value(4))
				.andExpect(jsonPath("$.total_pages").value(1));
		}

		@Test
		void pageSizeAboveTheLimit() throws Exception {
			mvc.perform(as("CLIENT001", get("/notification_events").param("size", "101")))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
		}

	}

	@Nested
	class GetNotificationEventDetail {

		@Autowired
		private JdbcClient jdbc;

		@BeforeEach
		void recordAttemptsOutOfOrder() {
			jdbc.sql("""
					INSERT INTO delivery_attempts
					    (notification_id, round, attempt_number, attempted_at, http_status, error_type, latency_ms)
					VALUES ('EVT003', 2, 1, '2024-03-16T08:00:00Z', 500, 'server_error', 80),
					       ('EVT003', 1, 2, '2024-03-15T11:20:18Z', 503, 'server_error', 120),
					       ('EVT003', 1, 1, '2024-03-15T11:20:08Z', NULL, 'timeout', 5000)
					ON CONFLICT DO NOTHING
					""").update();
		}

		@Test
		void clientReadsItsOwnNotificationEvent() throws Exception {
			mvc.perform(as("CLIENT002", get("/notification_events/EVT003")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.notification_event_id").value("EVT003"))
				.andExpect(jsonPath("$.delivery_status").value("failed"))
				.andExpect(jsonPath("$.attempts[*].round").value(contains(1, 1, 2)))
				.andExpect(jsonPath("$.attempts[*].attempt_number").value(contains(1, 2, 1)))
				.andExpect(jsonPath("$.attempts[0].attempted_at").value("2024-03-15T11:20:08Z"))
				.andExpect(jsonPath("$.attempts[0].http_status").value(nullValue()))
				.andExpect(jsonPath("$.attempts[0].error_type").value("timeout"))
				.andExpect(jsonPath("$.attempts[0].latency_ms").value(5000))
				.andExpect(jsonPath("$.attempts[1].http_status").value(503));
		}

		@Test
		void notificationEventOfAnotherClientIsNotFound() throws Exception {
			mvc.perform(as("CLIENT001", get("/notification_events/EVT003")))
				.andExpect(status().isNotFound())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
		}

		@Test
		void unknownNotificationEvent() throws Exception {
			mvc.perform(as("CLIENT001", get("/notification_events/DOES-NOT-EXIST")))
				.andExpect(status().isNotFound());
		}

	}

	@Nested
	class SampleDataSeeding {

		@Autowired
		private SampleDataSeeder seeder;

		@Autowired
		private JdbcClient jdbc;

		@Test
		void seedLoadsOnce() {
			seeder.seed();

			assertThat(count("notifications")).isEqualTo(10);
			assertThat(count("subscriptions")).isEqualTo(3);
		}

		@Test
		void failedSeedRecordHasNoSuccessfulDelivery() throws Exception {
			mvc.perform(as("CLIENT003", get("/notification_events/EVT005")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.created_at").value("2024-03-15T13:45:10Z"))
				.andExpect(jsonPath("$.delivery_date").value("2024-03-15T13:45:10Z"))
				.andExpect(jsonPath("$.delivered_at").value(nullValue()));
		}

		private long count(String table) {
			return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
		}

	}

	@Nested
	class ContentNeverCrossesClients {

		@Test
		void everyListedItemBelongsToTheCaller() throws Exception {
			mvc.perform(as("CLIENT002", get("/notification_events").param("size", "100")))
				.andExpect(jsonPath("$.data[*].notification_event_id")
					.value(everyItem(is(oneOf("EVT003", "EVT004", "EVT008")))));
		}

	}

	static MockHttpServletRequestBuilder as(String clientId, MockHttpServletRequestBuilder request) {
		return request.header(HttpHeaders.AUTHORIZATION,
				"Bearer " + TestJwtIssuer.trusted().token(clientId, "notifications:read"));
	}

}
