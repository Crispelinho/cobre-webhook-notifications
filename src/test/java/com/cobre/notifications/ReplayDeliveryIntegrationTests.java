package com.cobre.notifications;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

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
import com.github.tomakehurst.wiremock.http.Request;
import com.github.tomakehurst.wiremock.matching.MatchResult;

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

	@Test
	void aFailedNotificationEventOfADeactivatedSubscriptionIsReplayedToItsSnapshotAndSignedWithItsSecret()
			throws Exception {
		receiver.stubFor(post("/deactivated").willReturn(aResponse().withStatus(400)));
		String client = "C-" + UUID.randomUUID().toString().substring(0, 8);
		String id = ingestFor(client, "/deactivated");
		awaitStatus(id, "failed");
		// Deactivating stops matching new events; it does not invalidate the notification event that already exists.
		// The subscription URL then changes, so only the stored snapshot can lead to /deactivated.
		jdbc.sql("UPDATE subscriptions SET active = false, url = :url WHERE id = :id")
			.param("url", "https://hooks.example.org/changed")
			.param("id", "SUB-" + client)
			.update();

		receiver.resetAll();
		receiver.stubFor(post("/deactivated").andMatching(request -> MatchResult.of(verifies(request, "whsec_test_default")))
			.willReturn(aResponse().withStatus(200)));
		receiver.stubFor(post("/deactivated").atPriority(10).willReturn(aResponse().withStatus(401)));
		mvc.perform(MockMvcRequestBuilders.post("/notification_events/" + id + "/replay")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ISSUER.token(client, "notifications:replay")))
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.round").value(2));

		awaitStatus(id, "completed");

		// A transient transport error before the first HTTP answer can add an attempt without a status (seen when Kafka
		// is still electing its group coordinator). The assertion is on the end of the history: the round that
		// received 400 and the replay that received 200.
		assertThat(attempts(id)).extracting(a -> a.get("http_status")).endsWith(400, 200);
		receiver.verify(1, postRequestedFor(urlEqualTo("/deactivated")).withHeader("Cobre-Signature",
				matching("t=[0-9]+,v1=[0-9a-f]{64}")));
		receiver.verify(0, postRequestedFor(urlEqualTo("/changed")));
	}

	@Test
	void aReplayToADestinationThatNowResolvesPrivatelyFailsWithoutSendingARequest() throws Exception {
		receiver.stubFor(post("/replay-private").willReturn(aResponse().withStatus(400)));
		String client = "C-" + UUID.randomUUID().toString().substring(0, 8);
		String id = ingestFor(client, "/replay-private");
		awaitStatus(id, "failed");
		// The notification keeps the URL it was created with. Simulate that this URL now resolves to a private address.
		jdbc.sql("UPDATE notifications SET destination_url = :url WHERE id = :id")
			.param("url", "https://private.example.org/replay-private")
			.param("id", id)
			.update();

		mvc.perform(MockMvcRequestBuilders.post("/notification_events/" + id + "/replay")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ISSUER.token(client, "notifications:replay")))
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.round").value(2));

		awaitAttemptInRound(id, 2);
		awaitStatus(id, "failed");
		assertThat(jdbc.sql("SELECT http_status IS NULL FROM delivery_attempts WHERE notification_id = :id AND round = 2")
			.param("id", id)
			.query(Boolean.class)
			.single()).isTrue();
		assertThat(jdbc.sql("SELECT error_type FROM delivery_attempts WHERE notification_id = :id AND round = 2")
			.param("id", id)
			.query(String.class)
			.single()).isEqualTo("invalid_destination");
		// Only the first round's attempt reached the receiver.
		receiver.verify(1, postRequestedFor(urlEqualTo("/replay-private")));
	}

	/**
	 * A subscription whose secret was removed is replayed: the replay is accepted, the attempt fails permanently as
	 * missing_secret and nothing is sent. Passing through the join of the delivery query also shows that the
	 * notification is still found when its subscription has no secret.
	 */
	@Test
	void aReplayOfANotificationWhoseSubscriptionHasNoSecretFailsPermanentlyWithoutSendingARequest() throws Exception {
		receiver.stubFor(post("/replay-unsigned").willReturn(aResponse().withStatus(400)));
		String client = "C-" + UUID.randomUUID().toString().substring(0, 8);
		String id = ingestFor(client, "/replay-unsigned");
		awaitStatus(id, "failed");
		jdbc.sql("UPDATE subscriptions SET secret_ciphertext = NULL WHERE id = :id")
			.param("id", "SUB-" + client)
			.update();

		mvc.perform(MockMvcRequestBuilders.post("/notification_events/" + id + "/replay")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ISSUER.token(client, "notifications:replay")))
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.round").value(2));

		awaitAttemptInRound(id, 2);
		awaitStatus(id, "failed");
		String errorType = jdbc.sql("SELECT error_type FROM delivery_attempts WHERE notification_id = :id AND round = 2")
			.param("id", id)
			.query(String.class)
			.single();
		assertThat(errorType).isEqualTo("missing_secret");
		assertThat(jdbc.sql("SELECT last_error FROM notifications WHERE id = :id")
			.param("id", id)
			.query(String.class)
			.single()).isEqualTo("missing_secret");
		// Only the first round's attempt reached the receiver.
		receiver.verify(1, postRequestedFor(urlEqualTo("/replay-unsigned")));
	}

	private void awaitAttemptInRound(String id, int round) {
		await().atMost(Duration.ofSeconds(60))
			.pollInterval(Duration.ofMillis(200))
			.untilAsserted(() -> assertThat(jdbc
				.sql("SELECT count(*) FROM delivery_attempts WHERE notification_id = :id AND round = :round")
				.param("id", id)
				.param("round", round)
				.query(Long.class)
				.single()).isPositive());
	}

	private static boolean verifies(Request request, String secret) {
		String header = request.getHeader("Cobre-Signature");
		if (header == null || !header.matches("t=\\d+,v1=[0-9a-f]{64}")) {
			return false;
		}
		String t = header.substring(2, header.indexOf(','));
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			mac.update((t + ".").getBytes(StandardCharsets.UTF_8));
			return MessageDigest.isEqual(mac.doFinal(request.getBody()),
					HexFormat.of().parseHex(header.substring(header.indexOf("v1=") + 3)));
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException(ex);
		}
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
