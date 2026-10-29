package com.cobre.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * One test per scenario in {@code openspec/changes/add-observability-and-audit/specs/platform-security/spec.md} (audit
 * log), over HTTP against the seeded sample data. The audit entries are read from the {@code audit} logger.
 */
@SpringBootTest(properties = { "app.seed.enabled=true",
		// Replays change the seeded rows: own context and containers, like ReplayAcceptanceTests.
		"test.isolated-database=audit-acceptance" })
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AuditAcceptanceTests {

	private static final TestJwtIssuer ISSUER = TestJwtIssuer.trusted();

	private final ListAppender<ILoggingEvent> audit = new ListAppender<>();

	private final Logger auditLogger = (Logger) LoggerFactory.getLogger("audit");

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@BeforeEach
	void captureAuditLog() {
		audit.start();
		auditLogger.addAppender(audit);
	}

	@AfterEach
	void release() {
		auditLogger.detachAppender(audit);
		jdbc.sql("DELETE FROM subscriptions WHERE starts_with(client_id, 'AUDITTEST_')").update();
	}

	@Test
	void replayIsAudited() throws Exception {
		mvc.perform(as("CLIENT002", "notifications:replay", post("/notification_events/EVT003/replay")))
			.andExpect(status().isAccepted());

		assertThat(entries()).singleElement().satisfies(entry -> {
			assertThat(entry).containsEntry("audit_client_id", "CLIENT002")
				.containsEntry("audit_action", "notification_event.replay")
				.containsEntry("audit_resource_id", "EVT003")
				.containsEntry("audit_outcome", "success");
			assertThat(entry.get("audit_trace_id")).isNotBlank();
		});
	}

	@Test
	void rejectedReplayOfACompletedEventIsAudited() throws Exception {
		mvc.perform(as("CLIENT001", "notifications:replay", post("/notification_events/EVT001/replay")))
			.andExpect(status().isConflict());

		assertThat(entries()).singleElement().satisfies(entry -> {
			assertThat(entry).containsEntry("audit_client_id", "CLIENT001")
				.containsEntry("audit_action", "notification_event.replay")
				.containsEntry("audit_resource_id", "EVT001")
				.containsEntry("audit_outcome", "rejected");
			assertThat(entry.get("audit_trace_id")).isNotBlank();
		});
	}

	@Test
	void subscriptionCreateUpdateAndDeactivateAreEachAuditedOnce() throws Exception {
		String client = "AUDITTEST_SUBS";
		String created = mvc
			.perform(as(client, "subscriptions:write", post("/subscriptions"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"url\":\"https://hooks.example.org/cobre\",\"event_types\":[\"credit_deposit\"]}"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.subscription_id").isNotEmpty())
			.andReturn()
			.getResponse()
			.getContentAsString();
		String id = JsonPath.read(created, "$.subscription_id");

		mvc.perform(as(client, "subscriptions:write", patch("/subscriptions/" + id))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"event_types\":[\"credit_deposit\",\"debit_withdrawal\"]}")).andExpect(status().isOk());
		mvc.perform(as(client, "subscriptions:write", delete("/subscriptions/" + id)))
			.andExpect(status().isNoContent());

		assertThat(entries()).extracting(entry -> entry.get("audit_action"))
			.containsExactly("subscription.create", "subscription.update", "subscription.deactivate");
		assertThat(entries()).allSatisfy(entry -> {
			assertThat(entry).containsEntry("audit_client_id", client)
				.containsEntry("audit_resource_id", id)
				.containsEntry("audit_outcome", "success");
			assertThat(entry.get("audit_trace_id")).isNotBlank();
		});
	}

	@Test
	void aRejectedSubscriptionCreationIsAudited() throws Exception {
		mvc.perform(as("AUDITTEST_BAD", "subscriptions:write", post("/subscriptions"))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"url\":\"https://not-allowed.example.net/\",\"event_types\":[\"credit_deposit\"]}"))
			.andExpect(status().isBadRequest());

		assertThat(entries()).singleElement().satisfies(entry -> assertThat(entry)
			.containsEntry("audit_client_id", "AUDITTEST_BAD")
			.containsEntry("audit_action", "subscription.create")
			.containsEntry("audit_outcome", "rejected"));
	}

	@Test
	void auditEntriesNeverCarryTheDestinationOrTheSecret() throws Exception {
		mvc.perform(as("AUDITTEST_PII", "subscriptions:write", post("/subscriptions"))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"url\":\"https://hooks.example.org/cobre\",\"event_types\":[\"credit_deposit\"]}"))
			.andExpect(status().isCreated());

		assertThat(audit.list).singleElement().satisfies(event -> {
			assertThat(event.getFormattedMessage()).doesNotContain("hooks.example.org").doesNotContain("whsec_");
			assertThat(event.getKeyValuePairs()).noneMatch(pair -> String.valueOf(pair.value).contains("whsec_")
					|| String.valueOf(pair.value).contains("hooks.example.org"));
		});
	}

	private List<Map<String, String>> entries() {
		return audit.list.stream()
			.map(event -> event.getKeyValuePairs()
				.stream()
				.collect(Collectors.toMap(pair -> pair.key, pair -> String.valueOf(pair.value))))
			.toList();
	}

	private static MockHttpServletRequestBuilder as(String clientId, String scope,
			MockHttpServletRequestBuilder request) {
		return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + ISSUER.token(clientId, scope));
	}

}
