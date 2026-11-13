package com.cobre.notifications.notifications.adapters.in.seed;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.cobre.notifications.shared.crypto.SecretCipher;
import com.fasterxml.jackson.annotation.JsonProperty;

import tools.jackson.databind.json.JsonMapper;

/**
 * Loads {@code seed/notification_events.json} as notification events, with one seed subscription per client. The
 * sample has no creation date, so {@code created_at} uses the sample {@code delivery_date}. Inserting is idempotent.
 * <p>
 * Every seed subscription is signed: the secret comes from external configuration ({@code app.seed.secret}, backed by
 * the {@code DEMO_WEBHOOK_SECRET} environment variable, with no value in the repository), is encrypted with
 * {@link SecretCipher} using the subscription id as associated data, and is re-applied at every startup so a row left
 * without a secret by an earlier run is repaired. A missing or blank secret fails startup: there is no unsigned seed
 * subscription.
 */
@Component
@ConditionalOnBooleanProperty("app.seed.enabled")
public class SampleDataSeeder {

	private static final Logger log = LoggerFactory.getLogger(SampleDataSeeder.class);

	private static final String SAMPLE = "seed/notification_events.json";

	private final JdbcClient jdbc;

	private final JsonMapper jsonMapper;

	private final SecretCipher secretCipher;

	private final byte[] secret;

	SampleDataSeeder(JdbcClient jdbc, JsonMapper jsonMapper, SecretCipher secretCipher,
			@Value("${app.seed.secret:}") String secret) {
		if (secret == null || secret.isBlank()) {
			throw new IllegalStateException("app.seed.secret is required when app.seed.enabled is true: set the "
					+ "DEMO_WEBHOOK_SECRET environment variable to a development-only webhook secret. Seed "
					+ "subscriptions are never created without a secret.");
		}
		this.jdbc = jdbc;
		this.jsonMapper = jsonMapper;
		this.secretCipher = secretCipher;
		this.secret = secret.strip().getBytes(StandardCharsets.UTF_8);
	}

	@EventListener(ApplicationReadyEvent.class)
	@Transactional
	public void seed() {
		List<SampleEvent> events = read().events();
		Map<String, TreeSet<String>> eventTypesByClient = events.stream()
			.collect(Collectors.groupingBy(SampleEvent::clientId,
					Collectors.mapping(SampleEvent::eventType, Collectors.toCollection(TreeSet::new))));

		eventTypesByClient.forEach(this::insertSubscription);
		events.forEach(this::insertEventAndNotification);
		log.info("Sample data loaded: {} notification events, {} seed subscriptions", events.size(),
				eventTypesByClient.size());
	}

	private void insertSubscription(String clientId, TreeSet<String> eventTypes) {
		jdbc.sql("""
				INSERT INTO subscriptions (id, client_id, url, event_types, secret_ciphertext, created_at)
				VALUES (:id, :clientId, :url, CAST(:eventTypes AS text[]), :secretCiphertext, :createdAt)
				ON CONFLICT (id) DO UPDATE SET secret_ciphertext = EXCLUDED.secret_ciphertext
				""")
			.param("id", subscriptionId(clientId))
			// Fresh nonce per run; the subscription id binds the ciphertext to this row.
			.param("secretCiphertext", secretCipher.encrypt(secret, subscriptionId(clientId)))
			.param("clientId", clientId)
			// Reserved domain: seed subscriptions are placeholders until real ones are registered.
			.param("url", "https://webhook.example.com/" + clientId)
			// Array literal; sample event types are plain identifiers, so no quoting is needed.
			.param("eventTypes", "{" + String.join(",", eventTypes) + "}")
			.param("createdAt", OffsetDateTime.now(ZoneOffset.UTC))
			.update();
	}

	private void insertEventAndNotification(SampleEvent event) {
		OffsetDateTime createdAt = utc(event.deliveryDate());
		jdbc.sql("""
				INSERT INTO events (id, client_id, event_type, content, created_at)
				VALUES (:id, :clientId, :eventType, :content, :createdAt)
				ON CONFLICT (id) DO NOTHING
				""")
			.param("id", event.eventId())
			.param("clientId", event.clientId())
			.param("eventType", event.eventType())
			.param("content", event.content())
			.param("createdAt", createdAt)
			.update();
		jdbc.sql("""
				INSERT INTO notifications (id, event_id, subscription_id, client_id, destination_url, delivery_status,
				                           created_at, delivery_date, delivered_at)
				VALUES (:id, :id, :subscriptionId, :clientId, :url, :status, :createdAt, :deliveryDate, :deliveredAt)
				ON CONFLICT (id) DO NOTHING
				""")
			.param("id", event.eventId())
			.param("subscriptionId", subscriptionId(event.clientId()))
			.param("clientId", event.clientId())
			.param("url", "https://webhook.example.com/" + event.clientId())
			.param("status", event.deliveryStatus())
			.param("createdAt", createdAt)
			// delivery_date is the final outcome, so it exists only for completed or failed records.
			.param("deliveryDate", isFinal(event.deliveryStatus()) ? createdAt : null, Types.TIMESTAMP_WITH_TIMEZONE)
			.param("deliveredAt", "completed".equals(event.deliveryStatus()) ? createdAt : null,
					Types.TIMESTAMP_WITH_TIMEZONE)
			.update();
	}

	private SampleFile read() {
		try (InputStream json = new ClassPathResource(SAMPLE).getInputStream()) {
			return jsonMapper.readValue(json, SampleFile.class);
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Cannot read " + SAMPLE, ex);
		}
	}

	private static boolean isFinal(String deliveryStatus) {
		return "completed".equals(deliveryStatus) || "failed".equals(deliveryStatus);
	}

	private static String subscriptionId(String clientId) {
		return "SUB-" + clientId;
	}

	private static OffsetDateTime utc(Instant instant) {
		return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
	}

	record SampleFile(List<SampleEvent> events) {
	}

	record SampleEvent(@JsonProperty("event_id") String eventId, @JsonProperty("event_type") String eventType,
			String content, @JsonProperty("delivery_date") Instant deliveryDate,
			@JsonProperty("delivery_status") String deliveryStatus, @JsonProperty("client_id") String clientId) {
	}

}
