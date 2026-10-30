package com.cobre.notifications;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
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
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.kafka.KafkaContainer;

import com.cobre.notifications.shared.crypto.SecretCipher;
import com.cobre.notifications.support.WebhookTestServer;
import com.github.tomakehurst.wiremock.http.Request;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.matching.MatchResult;

/**
 * Delivery end to end against real PostgreSQL and Kafka and a WireMock receiver over HTTPS: ingestion, outbox relay,
 * dispatch consumer, retry topics and dead-letter topic. Backoff delays are shortened with a property (the production
 * code path is the same); no test waits for a real backoff interval. The only TLS concession is a test
 * {@code HttpClient} bean that trusts the test certificate; production TLS is covered by
 * {@code HttpsWebhookSenderTests}.
 */
@SpringBootTest(properties = { "app.delivery.consumer.enabled=true", "app.outbox.relay.poll-interval=PT0.2S",
		"app.delivery.retry.delays=PT0.2S,PT0.3S,PT0.4S,PT0.5S", "app.delivery.http.request-timeout=PT3S",
		"app.delivery.retry-after-cap=PT3S" })
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, DeliveryIntegrationTests.TrustTestCertificate.class })
class DeliveryIntegrationTests {

	private static final TestJwtIssuer ISSUER = TestJwtIssuer.trusted();

	@RegisterExtension
	static WireMockExtension receiver = WebhookTestServer.extension();

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private KafkaContainer kafka;

	@Autowired
	private SecretCipher secretCipher;

	@Autowired
	private KafkaTemplate<String, String> kafkaTemplate;

	@TestConfiguration(proxyBeanMethods = false)
	static class TrustTestCertificate {

		@Bean
		@Primary
		CloseableHttpClient testWebhookHttpClient() {
			// Destinations must pass the production destination policy (https, port 443, allowlisted public host), so
			// subscriptions use https://hooks.example.org/... (see FakeDnsConfiguration). The production client connects
			// to the validated public address; this test client redirects that socket to the local TLS receiver, still
			// verifying the certificate and the host name.
			return WebhookTestServer.trustingHttpClient(receiver::getHttpsPort);
		}

	}

	@Test
	void receiverAcceptsTheWebhookOnTheFirstTry() throws Exception {
		receiver.stubFor(post("/ok").willReturn(aResponse().withStatus(200)));

		String id = ingestFor("/ok");
		awaitStatus(id, "completed");

		Map<String, Object> notification = notification(id);
		assertThat(notification.get("delivery_date")).isNotNull();
		assertThat(notification.get("delivered_at")).isNotNull();
		assertThat(notification.get("attempts")).isEqualTo(1);
		assertThat(notification.get("last_error")).isNull();
		assertThat(attempts(id)).singleElement().satisfies(attempt -> {
			assertThat(attempt.get("http_status")).isEqualTo(200);
			assertThat(attempt.get("attempt_number")).isEqualTo(1);
			assertThat(attempt.get("round")).isEqualTo(1);
			assertThat(attempt.get("error_type")).isNull();
		});
		receiver.verify(1, postRequestedFor(urlEqualTo("/ok")));
	}

	@Test
	void serverErrorsAreRetriedUntilTheReceiverSucceeds() throws Exception {
		receiver.stubFor(post("/flaky").inScenario("flaky")
			.whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
			.willReturn(aResponse().withStatus(503))
			.willSetStateTo("second"));
		receiver.stubFor(post("/flaky").inScenario("flaky")
			.whenScenarioStateIs("second")
			.willReturn(aResponse().withStatus(503))
			.willSetStateTo("third"));
		receiver.stubFor(post("/flaky").inScenario("flaky")
			.whenScenarioStateIs("third")
			.willReturn(aResponse().withStatus(200)));

		String id = ingestFor("/flaky");
		awaitStatus(id, "completed");

		assertThat(attempts(id)).extracting(a -> a.get("http_status")).containsExactly(503, 503, 200);
		assertThat(attempts(id)).extracting(a -> a.get("attempt_number")).containsExactly(1, 2, 3);
		assertThat(notification(id).get("attempts")).isEqualTo(3);
		assertThat(notification(id).get("delivered_at")).isNotNull();
	}

	@Test
	void aRetryAfterDelayIsNotShortenedByTheNextAttempt() throws Exception {
		receiver.stubFor(post("/throttled").inScenario("throttled")
			.whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
			.willReturn(aResponse().withStatus(429).withHeader("Retry-After", "2"))
			.willSetStateTo("recovered"));
		receiver.stubFor(post("/throttled").inScenario("throttled")
			.whenScenarioStateIs("recovered")
			.willReturn(aResponse().withStatus(200)));

		String id = ingestFor("/throttled");
		awaitStatus(id, "completed");

		assertThat(attempts(id)).extracting(a -> a.get("http_status")).containsExactly(429, 200);
		// The receiver asked for 2 s: the second attempt is stored with next_attempt_at and never sent earlier (D53, D63).
		List<java.time.Instant> times = jdbc.sql("SELECT attempted_at FROM delivery_attempts "
				+ "WHERE notification_id = :id ORDER BY attempt_number")
			.param("id", id)
			.query(java.time.Instant.class)
			.list();
		assertThat(Duration.between(times.get(0), times.get(1))).isGreaterThanOrEqualTo(Duration.ofSeconds(2));
	}

	@Test
	void aRetryAfterAboveTheCapIsScheduledAtTheCapAndNotAnHourLater() throws Exception {
		receiver.stubFor(post("/over-cap").inScenario("over-cap")
			.whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
			.willReturn(aResponse().withStatus(429).withHeader("Retry-After", "3600"))
			.willSetStateTo("recovered"));
		receiver.stubFor(post("/over-cap").inScenario("over-cap")
			.whenScenarioStateIs("recovered")
			.willReturn(aResponse().withStatus(200)));

		String id = ingestFor("/over-cap");
		// The cap is 3 s (see @SpringBootTest), so the retry must arrive within the await, not after an hour.
		awaitStatus(id, "completed");

		assertThat(attempts(id)).extracting(a -> a.get("http_status")).containsExactly(429, 200);
		List<java.time.Instant> times = jdbc.sql("SELECT attempted_at FROM delivery_attempts "
				+ "WHERE notification_id = :id ORDER BY attempt_number")
			.param("id", id)
			.query(java.time.Instant.class)
			.list();
		Duration gap = Duration.between(times.get(0), times.get(1));
		assertThat(gap).isGreaterThanOrEqualTo(Duration.ofSeconds(3));
		assertThat(gap).isLessThan(Duration.ofSeconds(30));
	}

	@Test
	void aRetryAfterOnEveryAttemptStillEndsTheRoundAfterFiveAttempts() throws Exception {
		receiver.stubFor(post("/always-throttled").willReturn(aResponse().withStatus(429).withHeader("Retry-After", "1")));

		String id = ingestFor("/always-throttled");
		awaitStatus(id, "failed");

		// Retry-After never adds attempts: the round still stops at five (D18, D49).
		assertThat(attempts(id)).hasSize(5);
		assertThat(attempts(id)).extracting(a -> a.get("http_status")).containsOnly(429);
		assertThat(attempts(id)).extracting(a -> a.get("round")).containsOnly(1);
		assertThat(notification(id).get("attempts")).isEqualTo(5);
	}

	@Test
	void aPermanentErrorFailsAfterOneAttemptWithoutRetry() throws Exception {
		receiver.stubFor(post("/gone").willReturn(aResponse().withStatus(400)));

		String id = ingestFor("/gone");
		awaitStatus(id, "failed");
		// Longer than the whole retry schedule of this test (1.4 s): a retry would show up by now.
		Thread.sleep(2_000);

		assertThat(attempts(id)).singleElement().satisfies(attempt -> {
			assertThat(attempt.get("http_status")).isEqualTo(400);
			assertThat(attempt.get("error_type")).isEqualTo("http_400");
		});
		assertThat(notification(id).get("delivery_date")).isNotNull();
		assertThat(notification(id).get("delivered_at")).isNull();
		receiver.verify(1, postRequestedFor(urlEqualTo("/gone")));
	}

	@Test
	void aSlowReceiverIsRecordedAsATimeoutAndRetried() throws Exception {
		receiver.stubFor(post("/slow").inScenario("slow")
			.whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
			.willReturn(aResponse().withStatus(200).withFixedDelay(5_000))
			.willSetStateTo("fast"));
		receiver.stubFor(post("/slow").inScenario("slow")
			.whenScenarioStateIs("fast")
			.willReturn(aResponse().withStatus(200)));

		String id = ingestFor("/slow");
		awaitStatus(id, "completed");

		List<Map<String, Object>> attempts = attempts(id);
		assertThat(attempts).hasSize(2);
		assertThat(attempts.get(0).get("error_type")).isEqualTo("timeout");
		assertThat(attempts.get(0).get("http_status")).isNull();
		assertThat(attempts.get(1).get("http_status")).isEqualTo(200);
	}

	@Test
	void aRedirectIsAPermanentFailureAndIsNotFollowed() throws Exception {
		receiver.stubFor(post("/moved").willReturn(aResponse().withStatus(302)
			.withHeader("Location", "https://hooks.example.org/elsewhere")));
		receiver.stubFor(post("/elsewhere").willReturn(aResponse().withStatus(200)));

		String id = ingestFor("/moved");
		awaitStatus(id, "failed");
		Thread.sleep(1_500);

		assertThat(attempts(id)).singleElement().satisfies(attempt -> {
			assertThat(attempt.get("http_status")).isEqualTo(302);
			assertThat(attempt.get("error_type")).isEqualTo("http_302");
		});
		receiver.verify(1, postRequestedFor(urlEqualTo("/moved")));
		receiver.verify(0, anyRequestedFor(urlEqualTo("/elsewhere")));
	}

	@Test
	void exhaustedRetriesFailTheEventAndReachTheDeadLetterTopic() throws Exception {
		receiver.stubFor(post("/down").willReturn(aResponse().withStatus(500)));

		String id = ingestFor("/down");
		awaitStatus(id, "failed");

		assertThat(attempts(id)).hasSize(5)
			.allSatisfy(attempt -> assertThat(attempt.get("http_status")).isEqualTo(500));
		Map<String, Object> notification = notification(id);
		assertThat(notification.get("attempts")).isEqualTo(5);
		assertThat(notification.get("delivery_date")).isNotNull();
		assertThat(notification.get("delivered_at")).isNull();
		assertThat(notification.get("last_error")).isEqualTo("http_500");
		receiver.verify(5, postRequestedFor(urlEqualTo("/down")));
		await().atMost(Duration.ofSeconds(30)).until(() -> !recordsOf("notification.dispatch-dlt", id).isEmpty());
		assertThat(recordsOf("notification.dispatch-retry-0", id)).isNotEmpty();
		assertThat(recordsOf("notification.dispatch-retry-3", id)).isNotEmpty();
	}

	@Test
	void aDuplicateMessageForACompletedEventSendsNothingAndRecordsNothing() throws Exception {
		receiver.stubFor(post("/once").willReturn(aResponse().withStatus(200)));
		String id = ingestFor("/once");
		awaitStatus(id, "completed");

		kafkaTemplate.send("notification.dispatch", id, dispatchPayload(id, 1)).get(10, TimeUnit.SECONDS);
		awaitDispatchTopicConsumed();

		receiver.verify(1, postRequestedFor(urlEqualTo("/once")));
		assertThat(attempts(id)).hasSize(1);
		assertThat(notification(id).get("attempts")).isEqualTo(1);
	}

	@Test
	void aMessageFromAnEarlierRoundIsIgnored() throws Exception {
		receiver.stubFor(post("/replayed").willReturn(aResponse().withStatus(200)));
		String id = ingestFor("/replayed");
		awaitStatus(id, "completed");
		// Simulates a replay that already moved the event to round 2 and is waiting for its own message.
		jdbc.sql("UPDATE notifications SET round = 2, delivery_status = 'pending', delivery_date = NULL WHERE id = :id")
			.param("id", id)
			.update();

		kafkaTemplate.send("notification.dispatch", id, dispatchPayload(id, 1)).get(10, TimeUnit.SECONDS);
		awaitDispatchTopicConsumed();

		receiver.verify(1, postRequestedFor(urlEqualTo("/replayed")));
		assertThat(notification(id).get("delivery_status")).isEqualTo("pending");
		assertThat(attempts(id)).hasSize(1);
	}

	@Test
	void anUnreadableMessageGoesToTheDeadLetterTopicWithoutRetries() throws Exception {
		String key = "poison-" + UUID.randomUUID();

		kafkaTemplate.send("notification.dispatch", key, "not json").get(10, TimeUnit.SECONDS);

		await().atMost(Duration.ofSeconds(30)).until(() -> !recordsOf("notification.dispatch-dlt", key).isEmpty());
		assertThat(recordsOf("notification.dispatch-retry-0", key)).isEmpty();
	}

	@Test
	void aDestinationResolvingToAPrivateAddressFailsPermanentlyWithoutSendingARequest() throws Exception {
		receiver.stubFor(post("/private").willReturn(aResponse().withStatus(200)));

		String id = ingestFor("https://private.example.org/private", null);
		awaitStatus(id, "failed");
		Thread.sleep(1_500);

		assertThat(attempts(id)).singleElement().satisfies(attempt -> {
			assertThat(attempt.get("http_status")).isNull();
			assertThat(attempt.get("error_type")).isEqualTo("invalid_destination");
		});
		assertThat(notification(id).get("last_error")).isEqualTo("invalid_destination");
		receiver.verify(0, anyRequestedFor(urlEqualTo("/private")));
	}

	@Test
	void theWebhookIsSignedWithTheSubscriptionSecretAndTheReceiverCanVerifyIt() throws Exception {
		String secret = "whsec_integration-secret";
		receiver.stubFor(post("/signed").andMatching(request -> MatchResult.of(verifies(request, secret)))
			.willReturn(aResponse().withStatus(200)));
		receiver.stubFor(post("/signed").atPriority(10).willReturn(aResponse().withStatus(401)));

		String id = ingestFor("https://hooks.example.org/signed", secret);
		awaitStatus(id, "completed");

		assertThat(attempts(id)).singleElement().satisfies(attempt -> assertThat(attempt.get("http_status")).isEqualTo(200));
	}

	@Test
	void aSignatureMadeWithAnotherSecretIsNeverAccepted() throws Exception {
		receiver.stubFor(post("/strict").andMatching(request -> MatchResult.of(verifies(request, "whsec_expected")))
			.willReturn(aResponse().withStatus(200)));
		receiver.stubFor(post("/strict").atPriority(10).willReturn(aResponse().withStatus(401)));

		String id = ingestFor("https://hooks.example.org/strict", "whsec_someone-else");
		awaitStatus(id, "failed");

		assertThat(attempts(id)).singleElement().satisfies(attempt -> {
			assertThat(attempt.get("http_status")).isEqualTo(401);
			assertThat(attempt.get("error_type")).isEqualTo("http_401");
		});
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

	private String ingestFor(String path) throws Exception {
		return ingestFor("https://hooks.example.org" + path, "whsec_test_default");
	}

	/**
	 * @param secret plaintext secret to store encrypted for the subscription, (a subscription without a secret is never delivered)
	 */
	private String ingestFor(String url, String secret) throws Exception {
		String client = "C-" + UUID.randomUUID().toString().substring(0, 8);
		String subscriptionId = "SUB-" + client;
		jdbc.sql("INSERT INTO subscriptions (id, client_id, url, event_types, active, secret_ciphertext, created_at) "
				+ "VALUES (:id, :clientId, :url, :types, true, :secret, now())")
			.param("id", subscriptionId)
			.param("clientId", client)
			.param("url", url)
			.param("types", new String[] { "credit_deposit" })
			.param("secret", secret == null ? null : secretCipher.encrypt(secret.getBytes(StandardCharsets.UTF_8), subscriptionId))
			.update();
		String body = "{\"event_id\":\"E-%s\",\"client_id\":\"%s\",\"event_type\":\"credit_deposit\",\"content\":\"x\"}"
			.formatted(UUID.randomUUID(), client);
		String response = mvc
			.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/events")
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
			.untilAsserted(() -> assertThat(notification(id).get("delivery_status")).isEqualTo(expected));
	}

	private Map<String, Object> notification(String id) {
		return jdbc.sql("SELECT delivery_status, attempts, delivery_date, delivered_at, last_error FROM notifications "
				+ "WHERE id = :id")
			.param("id", id)
			.query()
			.singleRow();
	}

	private List<Map<String, Object>> attempts(String id) {
		return jdbc.sql("SELECT round, attempt_number, http_status, error_type FROM delivery_attempts "
				+ "WHERE notification_id = :id ORDER BY attempt_number")
			.param("id", id)
			.query()
			.listOfRows();
	}

	private static String dispatchPayload(String id, int round) {
		return "{\"notification_event_id\":\"%s\",\"round\":%d}".formatted(id, round);
	}

	/**
	 * Waits until the dispatch consumer group has committed every offset of the dispatch topic, which means the
	 * messages sent so far were processed (the consumer commits after the listener returns).
	 */
	private void awaitDispatchTopicConsumed() {
		Properties props = new Properties();
		props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
		props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
		props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
		try (Admin admin = Admin.create(Map.of("bootstrap.servers", kafka.getBootstrapServers()));
				KafkaConsumer<String, String> probe = new KafkaConsumer<>(props)) {
			List<TopicPartition> partitions = probe.partitionsFor("notification.dispatch")
				.stream()
				.map(info -> new TopicPartition(info.topic(), info.partition()))
				.toList();
			Map<TopicPartition, Long> end = probe.endOffsets(partitions);
			await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200)).until(() -> {
				Map<TopicPartition, OffsetAndMetadata> committed = admin.listConsumerGroupOffsets("notification-service")
					.partitionsToOffsetAndMetadata()
					.get();
				return partitions.stream().allMatch(tp -> {
					OffsetAndMetadata offset = committed.get(tp);
					return end.get(tp) == 0 || offset != null && offset.offset() >= end.get(tp);
				});
			});
		}
	}

	private List<ConsumerRecord<String, String>> recordsOf(String topic, String key) {
		Properties props = new Properties();
		props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
		props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
		props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
		props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
		props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
		List<ConsumerRecord<String, String>> matching = new ArrayList<>();
		try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
			var info = consumer.partitionsFor(topic);
			if (info == null || info.isEmpty()) {
				return matching;
			}
			List<TopicPartition> partitions = info.stream()
				.map(i -> new TopicPartition(i.topic(), i.partition()))
				.toList();
			consumer.assign(partitions);
			consumer.seekToBeginning(partitions);
			Map<TopicPartition, Long> end = consumer.endOffsets(partitions);
			while (partitions.stream().anyMatch(tp -> consumer.position(tp) < end.get(tp))) {
				consumer.poll(Duration.ofSeconds(1)).forEach(r -> {
					if (key.equals(r.key())) {
						matching.add(r);
					}
				});
			}
		}
		return matching;
	}

}
