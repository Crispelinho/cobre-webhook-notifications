package com.cobre.notifications;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.cobre.notifications.shared.crypto.SecretCipher;
import com.cobre.notifications.support.WebhookTestServer;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

/**
 * A replayed notification event goes through the real outbox relay, Kafka and the delivery consumer to a WireMock
 * receiver over HTTPS (scenario "Replayed notification event is delivered").
 */
@SpringBootTest(properties = { "app.delivery.consumer.enabled=true", "app.outbox.relay.poll-interval=PT0.2S",
		"app.delivery.retry.delays=PT0.2S,PT0.3S,PT0.4S,PT0.5S", "app.delivery.http.request-timeout=PT3S" })
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, ReplayDeliveryIntegrationTests.TrustTestCertificate.class })
class ReplayDeliveryIntegrationTests {

	private static final TestJwtIssuer ISSUER = TestJwtIssuer.trusted();

	@RegisterExtension
	static WireMockExtension receiver = WebhookTestServer.extension();

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private SecretCipher secretCipher;

	@TestConfiguration(proxyBeanMethods = false)
	static class TrustTestCertificate {

		@Bean
		@Primary
		CloseableHttpClient testWebhookHttpClient() {
			return WebhookTestServer.trustingHttpClient(receiver::getHttpsPort);
		}

	}

	@Test
	void aReplayedNotificationEventIsDeliveredInItsSecondRoundAndKeepsBothRoundsOfAttempts() throws Exception {
		receiver.stubFor(post("/replay-me").willReturn(aResponse().withStatus(400)));
		String client = "C-" + UUID.randomUUID().toString().substring(0, 8);
		String id = ingestFor(client, "/replay-me");
		awaitStatus(id, "failed");
		assertThat(attempts(id)).singleElement().satisfies(a -> assertThat(a.get("round")).isEqualTo(1));

		// The receiver is repaired; the client replays.
		receiver.resetAll();
		receiver.stubFor(post("/replay-me").willReturn(aResponse().withStatus(200)));
		mvc.perform(MockMvcRequestBuilders.post("/notification_events/" + id + "/replay")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ISSUER.token(client, "notifications:replay")))
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.round").value(2))
			.andExpect(jsonPath("$.delivery_status").value("pending"));

		awaitStatus(id, "completed");

		List<Map<String, Object>> attempts = attempts(id);
		assertThat(attempts).extracting(a -> a.get("round")).containsExactly(1, 2);
		// The attempt number restarts in the new round.
		assertThat(attempts).extracting(a -> a.get("attempt_number")).containsExactly(1, 1);
		assertThat(attempts).extracting(a -> a.get("http_status")).containsExactly(400, 200);
		Map<String, Object> notification = jdbc
			.sql("SELECT round, delivery_date, delivered_at FROM notifications WHERE id = :id")
			.param("id", id)
			.query()
			.singleRow();
		assertThat(notification.get("round")).isEqualTo(2);
		assertThat(notification.get("delivery_date")).isNotNull();
		assertThat(notification.get("delivered_at")).isNotNull();
		receiver.verify(1, postRequestedFor(urlEqualTo("/replay-me")));
	}

	private String ingestFor(String client, String path) throws Exception {
		jdbc.sql("INSERT INTO subscriptions (id, client_id, url, event_types, active, secret_ciphertext, created_at) "
				+ "VALUES (:id, :clientId, :url, :types, true, :secret, now())")
			.param("id", "SUB-" + client)
			.param("secret", secretCipher.encrypt("whsec_test_default".getBytes(StandardCharsets.UTF_8), "SUB-" + client))
			.param("clientId", client)
			.param("url", "https://hooks.example.org" + path)
			.param("types", new String[] { "credit_deposit" })
			.update();
		String body = "{\"event_id\":\"E-%s\",\"client_id\":\"%s\",\"event_type\":\"credit_deposit\",\"content\":\"x\"}"
			.formatted(UUID.randomUUID(), client);
		String response = mvc
			.perform(MockMvcRequestBuilders.post("/events")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + ISSUER.token("producer-service", "events:write"))
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
			.andExpect(status().isAccepted())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return com.jayway.jsonpath.JsonPath.read(response, "$.notification_event_ids[0]");
	}

	private void awaitStatus(String id, String expected) {
		await().atMost(Duration.ofSeconds(60))
			.pollInterval(Duration.ofMillis(200))
			.untilAsserted(() -> assertThat(jdbc.sql("SELECT delivery_status FROM notifications WHERE id = :id")
				.param("id", id)
				.query(String.class)
				.single()).isEqualTo(expected));
	}

	private List<Map<String, Object>> attempts(String id) {
		return jdbc.sql("SELECT round, attempt_number, http_status FROM delivery_attempts WHERE notification_id = :id "
				+ "ORDER BY round, attempt_number")
			.param("id", id)
			.query()
			.listOfRows();
	}

}
