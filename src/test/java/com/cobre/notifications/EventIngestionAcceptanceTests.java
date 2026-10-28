package com.cobre.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * One test per scenario in {@code openspec/changes/add-event-ingestion-and-delivery/specs} that concerns ingestion:
 * event ingestion, duplicate events, event validation and producer authorization. Each test uses its own client and
 * event ids, so tests do not depend on each other.
 */
@SpringBootTest(properties = "app.outbox.relay.enabled=false")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class EventIngestionAcceptanceTests {

	private static final TestJwtIssuer ISSUER = TestJwtIssuer.trusted();

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Nested
	class EventIngestion {

		@Test
		void eventMatchesASubscription() throws Exception {
			String client = uniqueClient();
			subscribe(client, "SUB-" + client, "https://hooks.example.com/" + client, true, "credit_deposit");
			String eventId = uniqueEvent();

			String body = ingest(producerToken(), event(eventId, client, "credit_deposit")).andExpect(status().isAccepted())
				.andExpect(jsonPath("$.event_id").value(eventId))
				.andExpect(jsonPath("$.notification_event_ids", hasSize(1)))
				.andReturn()
				.getResponse()
				.getContentAsString();

			String notificationId = com.jayway.jsonpath.JsonPath.read(body, "$.notification_event_ids[0]");
			assertThat(UUID.fromString(notificationId).version()).isEqualTo(7);
			Map<String, Object> row = jdbc
				.sql("SELECT delivery_status, destination_url, round, attempts, client_id, event_id, subscription_id "
						+ "FROM notifications WHERE id = :id")
				.param("id", notificationId)
				.query()
				.singleRow();
			assertThat(row).containsEntry("delivery_status", "pending")
				.containsEntry("destination_url", "https://hooks.example.com/" + client)
				.containsEntry("round", 1)
				.containsEntry("attempts", 0)
				.containsEntry("client_id", client)
				.containsEntry("event_id", eventId)
				.containsEntry("subscription_id", "SUB-" + client);
			assertThat(count("SELECT count(*) FROM events WHERE id = ? AND client_id = ?", eventId, client)).isEqualTo(1);
		}

		@Test
		void outboxRowCarriesOnlyTheNotificationEventIdAndRound() throws Exception {
			String client = uniqueClient();
			subscribe(client, "SUB-" + client, "https://hooks.example.com/" + client, true, "credit_deposit");

			String body = ingest(producerToken(), event(uniqueEvent(), client, "credit_deposit")).andReturn()
				.getResponse()
				.getContentAsString();

			String notificationId = com.jayway.jsonpath.JsonPath.read(body, "$.notification_event_ids[0]");
			Map<String, Object> outbox = jdbc
				.sql("SELECT payload ->> 'notification_event_id' AS id, (payload ->> 'round')::int AS round, "
						+ "(SELECT count(*) FROM jsonb_object_keys(payload)) AS keys, published_at "
						+ "FROM outbox WHERE notification_id = :id")
				.param("id", notificationId)
				.query()
				.singleRow();
			assertThat(outbox).containsEntry("id", notificationId)
				.containsEntry("round", 1)
				.containsEntry("keys", 2L)
				.containsEntry("published_at", null);
		}

		@Test
		void eventMatchesNoSubscription() throws Exception {
			String client = uniqueClient();
			subscribe(client, "SUB-" + client, "https://hooks.example.com/" + client, true, "credit_transfer");
			String eventId = uniqueEvent();

			ingest(producerToken(), event(eventId, client, "credit_deposit")).andExpect(status().isAccepted())
				.andExpect(jsonPath("$.event_id").value(eventId))
				.andExpect(jsonPath("$.notification_event_ids", empty()));

			assertThat(count("SELECT count(*) FROM notifications WHERE event_id = ?", eventId)).isZero();
			assertThat(count("SELECT count(*) FROM events WHERE id = ?", eventId)).isEqualTo(1);
		}

		@Test
		void subscriptionOfAnotherClientIsNeverMatched() throws Exception {
			String other = uniqueClient();
			String client = uniqueClient();
			subscribe(other, "SUB-" + other, "https://hooks.example.com/" + other, true, "credit_deposit");
			String eventId = uniqueEvent();

			ingest(producerToken(), event(eventId, client, "credit_deposit")).andExpect(status().isAccepted())
				.andExpect(jsonPath("$.notification_event_ids", empty()));

			assertThat(count("SELECT count(*) FROM notifications WHERE client_id = ?", other)).isZero();
			assertThat(count("SELECT count(*) FROM notifications WHERE event_id = ?", eventId)).isZero();
		}

		@Test
		void inactiveSubscriptionIsNotMatched() throws Exception {
			String client = uniqueClient();
			subscribe(client, "SUB-" + client, "https://hooks.example.com/" + client, false, "credit_deposit");
			String eventId = uniqueEvent();

			ingest(producerToken(), event(eventId, client, "credit_deposit")).andExpect(status().isAccepted())
				.andExpect(jsonPath("$.notification_event_ids", empty()));

			assertThat(count("SELECT count(*) FROM notifications WHERE event_id = ?", eventId)).isZero();
		}

		@Test
		void everyMatchingSubscriptionGetsItsOwnNotificationAndOutboxRow() throws Exception {
			String client = uniqueClient();
			subscribe(client, "SUB-A-" + client, "https://a.example.com/" + client, true, "credit_deposit");
			subscribe(client, "SUB-B-" + client, "https://b.example.com/" + client, true, "credit_deposit", "other");
			String eventId = uniqueEvent();

			ingest(producerToken(), event(eventId, client, "credit_deposit")).andExpect(status().isAccepted())
				.andExpect(jsonPath("$.notification_event_ids", hasSize(2)));

			assertThat(count("SELECT count(*) FROM notifications WHERE event_id = ?", eventId)).isEqualTo(2);
			assertThat(count("SELECT count(*) FROM outbox o JOIN notifications n ON n.id = o.notification_id "
					+ "WHERE n.event_id = ?", eventId))
				.isEqualTo(2);
		}

	}

	@Nested
	class DuplicateEvents {

		@Test
		void producerRetriesAnAcceptedEvent() throws Exception {
			String client = uniqueClient();
			subscribe(client, "SUB-" + client, "https://hooks.example.com/" + client, true, "credit_deposit");
			String eventId = uniqueEvent();
			ingest(producerToken(), event(eventId, client, "credit_deposit")).andExpect(status().isAccepted());

			ingest(producerToken(), event(eventId, client, "credit_deposit")).andExpect(status().isConflict())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.status").value(409));

			assertThat(count("SELECT count(*) FROM notifications WHERE event_id = ?", eventId)).isEqualTo(1);
			assertThat(count("SELECT count(*) FROM outbox o JOIN notifications n ON n.id = o.notification_id "
					+ "WHERE n.event_id = ?", eventId))
				.isEqualTo(1);
			assertThat(count("SELECT count(*) FROM events WHERE id = ?", eventId)).isEqualTo(1);
		}

	}

	@Nested
	class EventValidation {

		@Test
		void invalidEventWithAnEmptyClientId() throws Exception {
			String eventId = uniqueEvent();

			ingest(producerToken(), event(eventId, "", "credit_deposit")).andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

			assertThat(count("SELECT count(*) FROM events WHERE id = ?", eventId)).isZero();
		}

		@Test
		void missingFieldIsRejected() throws Exception {
			ingest(producerToken(), "{\"event_id\":\"" + uniqueEvent() + "\",\"client_id\":\"C\",\"event_type\":\"t\"}")
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
		}

		@Test
		void eventTypeNotMatchingThePatternIsRejected() throws Exception {
			ingest(producerToken(), event(uniqueEvent(), "CLIENT001", "Credit-Deposit"))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(content().string(not(containsString("Credit-Deposit"))));
		}

		@Test
		void eventIdLongerThan64CharactersIsRejected() throws Exception {
			ingest(producerToken(), event("e".repeat(65), "CLIENT001", "credit_deposit"))
				.andExpect(status().isBadRequest());
		}

		@Test
		void clientIdLongerThan64CharactersIsRejected() throws Exception {
			ingest(producerToken(), event(uniqueEvent(), "c".repeat(65), "credit_deposit"))
				.andExpect(status().isBadRequest());
		}

		@Test
		void contentLongerThan10000CharactersIsRejected() throws Exception {
			String eventId = uniqueEvent();

			ingest(producerToken(), json(eventId, "CLIENT001", "credit_deposit", "x".repeat(10_001)))
				.andExpect(status().isBadRequest());

			assertThat(count("SELECT count(*) FROM events WHERE id = ?", eventId)).isZero();
		}

		@Test
		void contentOfExactly10000CharactersIsAccepted() throws Exception {
			ingest(producerToken(), json(uniqueEvent(), uniqueClient(), "credit_deposit", "x".repeat(10_000)))
				.andExpect(status().isAccepted());
		}

		@Test
		void unknownFieldsAreRejected() throws Exception {
			String eventId = uniqueEvent();

			ingest(producerToken(), "{\"event_id\":\"" + eventId + "\",\"client_id\":\"CLIENT001\","
					+ "\"event_type\":\"credit_deposit\",\"content\":\"x\",\"admin\":true}")
				.andExpect(status().isBadRequest());

			assertThat(count("SELECT count(*) FROM events WHERE id = ?", eventId)).isZero();
		}

		@Test
		void malformedJsonIsRejected() throws Exception {
			ingest(producerToken(), "{not json").andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
		}

	}

	@Nested
	class ProducerAuthorization {

		@Test
		void clientTokenOnTheIngestionEndpoint() throws Exception {
			String client = uniqueClient();
			subscribe(client, "SUB-" + client, "https://hooks.example.com/" + client, true, "credit_deposit");
			String eventId = uniqueEvent();

			ingest(ISSUER.token(client, "notifications:read"), event(eventId, client, "credit_deposit"))
				.andExpect(status().isForbidden())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

			assertThat(count("SELECT count(*) FROM events WHERE id = ?", eventId)).isZero();
			assertThat(count("SELECT count(*) FROM notifications WHERE client_id = ?", client)).isZero();
		}

		@Test
		void producerTokenWithEventsWriteScope() throws Exception {
			ingest(producerToken(), event(uniqueEvent(), uniqueClient(), "credit_deposit"))
				.andExpect(status().isAccepted());
		}

		@Test
		void missingTokenIsUnauthorized() throws Exception {
			mvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON)
				.content(event(uniqueEvent(), "CLIENT001", "credit_deposit"))).andExpect(status().isUnauthorized());
		}

		@Test
		void producerScopeDoesNotOpenTheSelfServiceApi() throws Exception {
			mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/notification_events")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + producerToken())).andExpect(status().isForbidden());
		}

	}

	private ResultActions ingest(String token, String body) throws Exception {
		return mvc.perform(post("/events").header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
			.contentType(MediaType.APPLICATION_JSON)
			.content(body));
	}

	private static String producerToken() {
		return ISSUER.token("producer-service", "events:write");
	}

	private static String event(String eventId, String clientId, String eventType) {
		return json(eventId, clientId, eventType, "Deposit received");
	}

	private static String json(String eventId, String clientId, String eventType, String content) {
		return "{\"event_id\":\"%s\",\"client_id\":\"%s\",\"event_type\":\"%s\",\"content\":\"%s\"}".formatted(eventId,
				clientId, eventType, content);
	}

	private static String uniqueClient() {
		return "C-" + UUID.randomUUID().toString().substring(0, 8);
	}

	private static String uniqueEvent() {
		return "E-" + UUID.randomUUID();
	}

	private void subscribe(String clientId, String id, String url, boolean active, String... eventTypes) {
		jdbc.sql("INSERT INTO subscriptions (id, client_id, url, event_types, active, created_at) "
				+ "VALUES (:id, :clientId, :url, :types, :active, now())")
			.param("id", id)
			.param("clientId", clientId)
			.param("url", url)
			.param("types", eventTypes)
			.param("active", active)
			.update();
	}

	private int count(String sql, Object... params) {
		return jdbc.sql(sql).params(params).query(Integer.class).single();
	}

}
