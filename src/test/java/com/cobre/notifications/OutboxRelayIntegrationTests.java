package com.cobre.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.kafka.KafkaContainer;

import com.cobre.notifications.notifications.application.RelayOutbox;

/**
 * The outbox relay against a real PostgreSQL and a real Kafka broker. The scheduler is disabled so each test drives
 * the relay explicitly. "Kafka unavailable" is a real outage: the broker container is paused (its port stays bound
 * but nothing answers), which is what a hung or partitioned broker looks like to the producer.
 */
@SpringBootTest(properties = { "app.outbox.relay.enabled=false", "app.outbox.relay.publish-timeout=PT2S" })
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class OutboxRelayIntegrationTests {

	private static final TestJwtIssuer ISSUER = TestJwtIssuer.trusted();

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private RelayOutbox relay;

	@Autowired
	private KafkaContainer kafka;

	@Test
	void publishedRowGetsPublishedAtAndTheMessageHasTheRightKeyAndPayload() throws Exception {
		String notificationId = ingestOne();

		relay.relayPending();

		assertThat(publishedAt(notificationId)).isNotNull();
		ConsumerRecord<String, String> record = readFromTopic(notificationId);
		assertThat(record.key()).isEqualTo(notificationId);
		assertThat(record.value()).isEqualTo("{\"notification_event_id\":\"%s\",\"round\":1}".formatted(notificationId));
	}

	@Test
	void publishedRowsAreNotPublishedAgain() throws Exception {
		String notificationId = ingestOne();
		relay.relayPending();
		Object firstPublishedAt = publishedAt(notificationId);

		relay.relayPending();

		assertThat(publishedAt(notificationId)).isEqualTo(firstPublishedAt);
		assertThat(readAllFromTopic(notificationId)).hasSize(1);
	}

	@Test
	void eventAcceptedWhileKafkaIsUnavailableIsPublishedOnceKafkaIsBack() throws Exception {
		kafka.getDockerClient().pauseContainerCmd(kafka.getContainerId()).exec();
		String notificationId;
		try {
			// Ingestion does not touch Kafka, so the event is still accepted.
			notificationId = ingestOne();

			assertThat(relay.relayPending()).isZero();
			assertThat(publishedAt(notificationId)).isNull();
		}
		finally {
			kafka.getDockerClient().unpauseContainerCmd(kafka.getContainerId()).exec();
		}

		assertThat(relayUntilPublished(notificationId)).isTrue();
		assertThat(readFromTopic(notificationId).key()).isEqualTo(notificationId);
	}

	private boolean relayUntilPublished(String notificationId) throws InterruptedException {
		long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
		while (System.nanoTime() < deadline) {
			relay.relayPending();
			if (publishedAt(notificationId) != null) {
				return true;
			}
			Thread.sleep(500);
		}
		return false;
	}

	private String ingestOne() throws Exception {
		String client = "C-" + UUID.randomUUID().toString().substring(0, 8);
		jdbc.sql("INSERT INTO subscriptions (id, client_id, url, event_types, active, created_at) "
				+ "VALUES (:id, :clientId, :url, :types, true, now())")
			.param("id", "SUB-" + client)
			.param("clientId", client)
			.param("url", "https://hooks.example.com/" + client)
			.param("types", new String[] { "credit_deposit" })
			.update();
		String body = "{\"event_id\":\"E-%s\",\"client_id\":\"%s\",\"event_type\":\"credit_deposit\",\"content\":\"x\"}"
			.formatted(UUID.randomUUID(), client);
		String response = mvc
			.perform(post("/events")
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + ISSUER.token("producer-service", "events:write"))
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
			.andExpect(status().isAccepted())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return com.jayway.jsonpath.JsonPath.read(response, "$.notification_event_ids[0]");
	}

	private Object publishedAt(String notificationId) {
		Map<String, Object> row = jdbc.sql("SELECT published_at FROM outbox WHERE notification_id = :id")
			.param("id", notificationId)
			.query()
			.singleRow();
		return row.get("published_at");
	}

	private ConsumerRecord<String, String> readFromTopic(String key) {
		List<ConsumerRecord<String, String>> records = readAllFromTopic(key);
		// A publish that timed out during an outage may still reach the broker later: at-least-once, so duplicates are valid.
		assertThat(records).isNotEmpty();
		return records.get(0);
	}

	private List<ConsumerRecord<String, String>> readAllFromTopic(String key) {
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
			Map<TopicPartition, Long> end = consumer.endOffsets(partitions);
			while (partitions.stream().anyMatch(tp -> consumer.position(tp) < end.get(tp))) {
				consumer.poll(Duration.ofSeconds(2)).forEach(r -> {
					if (key.equals(r.key())) {
						matching.add(r);
					}
				});
			}
		}
		return matching;
	}

}
