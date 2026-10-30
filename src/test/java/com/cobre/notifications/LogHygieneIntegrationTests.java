package com.cobre.notifications;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.time.Duration;
import java.util.UUID;

import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.testcontainers.kafka.KafkaContainer;

import com.cobre.notifications.shared.crypto.SecretCipher;
import com.cobre.notifications.support.WebhookTestServer;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Structured logs (spec "Structured logs"): delivery attempts can be found by notification event ID, and neither the
 * event content, the signing secret nor the access token ever reaches the logs, on success or on failure.
 */
@SpringBootTest(properties = { "app.delivery.consumer.enabled=true", "app.outbox.relay.poll-interval=PT0.2S",
		"app.delivery.retry.delays=PT0.2S,PT0.3S,PT0.4S,PT0.5S", "app.delivery.http.request-timeout=PT3S",
		"test.isolated-database=log-hygiene" })
@AutoConfigureMockMvc(print = org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint.NONE)
@ExtendWith(OutputCaptureExtension.class)
@Import({ TestcontainersConfiguration.class, LogHygieneIntegrationTests.TrustTestCertificate.class })
class LogHygieneIntegrationTests {

	private static final TestJwtIssuer ISSUER = TestJwtIssuer.trusted();

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final String CONTENT_MARKER = "CONTENT-MARKER-4f1c9a-account-1234567890";

	private static final String SECRET = "whsec_log-hygiene-secret-9d2e";

	@RegisterExtension
	static WireMockExtension receiver = WebhookTestServer.extension();

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private SecretCipher secretCipher;

	@Autowired
	private KafkaContainer kafka;

	@TestConfiguration(proxyBeanMethods = false)
	static class TrustTestCertificate {

		@Bean
		@Primary
		CloseableHttpClient testWebhookHttpClient() {
			return WebhookTestServer.trustingHttpClient(receiver::getHttpsPort);
		}

	}

	@Test
	void everyAttemptIsFoundByNotificationEventIdAndNothingSensitiveIsLogged(CapturedOutput output) throws Exception {
		receiver.stubFor(post("/hygiene-ok").willReturn(aResponse().withStatus(200)));
		receiver.stubFor(post("/hygiene-bad").willReturn(aResponse().withStatus(400)));
		String delivered = ingestFor("/hygiene-ok");
		String refused = ingestFor("/hygiene-bad");
		awaitStatus(delivered, "completed");
		awaitStatus(refused, "failed");

		await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
			assertThat(jsonLinesWith(output, delivered))
				.anyMatch(json -> json.path("message").asString().contains("recorded: SUCCESS"));
			assertThat(jsonLinesWith(output, refused))
				.anyMatch(json -> json.path("message").asString().contains("recorded: PERMANENT"));
		});

		// Structured: the id is a top-level JSON member, not only text inside the message.
		assertThat(jsonLinesWith(output, delivered))
			.anyMatch(json -> delivered.equals(json.path("notification_event_id").asString()));

		String logs = output.getAll();
		assertThat(logs).doesNotContain(CONTENT_MARKER)
			.doesNotContain(SECRET)
			.doesNotContain("hooks.example.org")
			.doesNotContain("Bearer ")
			.doesNotContain(ISSUER.token("producer-service", "events:write"));
	}

	@Test
	void theTraceContinuesThroughKafkaHeadersIntoTheDeliveryLogs(CapturedOutput output) throws Exception {
		receiver.stubFor(post("/trace-ok").willReturn(aResponse().withStatus(200)));
		String id = ingestFor("/trace-ok");
		awaitStatus(id, "completed");

		List<ConsumerRecord<String, String>> records = dispatchRecords(id);
		assertThat(records).isNotEmpty();
		Header traceparent = records.getFirst().headers().lastHeader("traceparent");
		assertThat(traceparent).as("the relay sends the W3C trace context as a record header").isNotNull();
		String traceId = new String(traceparent.value(), StandardCharsets.UTF_8).split("-")[1];

		// The consumer continues the trace of the producer span, so its attempt log line has the same trace ID.
		await().atMost(Duration.ofSeconds(10))
			.untilAsserted(() -> assertThat(jsonLinesWith(output, id))
				.anyMatch(json -> json.path("message").asString().contains("recorded: SUCCESS")
						&& traceId.equals(json.path("traceId").asString())));
	}

	private List<ConsumerRecord<String, String>> dispatchRecords(String key) {
		Properties props = new Properties();
		props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
		props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
		props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
		props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
		props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
		List<ConsumerRecord<String, String>> matching = new ArrayList<>();
		try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
			List<TopicPartition> partitions = consumer.partitionsFor("notification.dispatch")
				.stream()
				.map(info -> new TopicPartition(info.topic(), info.partition()))
				.toList();
			consumer.assign(partitions);
			consumer.seekToBeginning(partitions);
			consumer.poll(Duration.ofSeconds(3)).forEach(record -> {
				if (key.equals(record.key())) {
					matching.add(record);
				}
			});
		}
		return matching;
	}

	/**
	 * The JSON log lines that mention the given ID, parsed. Lines that are not JSON objects (for example stack traces
	 * printed by the console) are skipped.
	 */
	private static List<JsonNode> jsonLinesWith(CapturedOutput output, String id) {
		return output.getAll()
			.lines()
			.filter(line -> line.startsWith("{") && line.contains(id))
			.map(line -> JSON.readTree(line))
			.toList();
	}

	private String ingestFor(String path) throws Exception {
		String client = "C-" + UUID.randomUUID().toString().substring(0, 8);
		String subscriptionId = "SUB-" + client;
		jdbc.sql("INSERT INTO subscriptions (id, client_id, url, event_types, active, secret_ciphertext, created_at) "
				+ "VALUES (:id, :clientId, :url, :types, true, :secret, now())")
			.param("id", subscriptionId)
			.param("clientId", client)
			.param("url", "https://hooks.example.org" + path)
			.param("types", new String[] { "credit_deposit" })
			.param("secret", secretCipher.encrypt(SECRET.getBytes(StandardCharsets.UTF_8), subscriptionId))
			.update();
		String body = "{\"event_id\":\"E-%s\",\"client_id\":\"%s\",\"event_type\":\"credit_deposit\",\"content\":\"%s\"}"
			.formatted(UUID.randomUUID(), client, CONTENT_MARKER);
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

}
