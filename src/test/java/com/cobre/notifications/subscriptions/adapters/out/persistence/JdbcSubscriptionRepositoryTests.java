package com.cobre.notifications.subscriptions.adapters.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.cobre.notifications.subscriptions.application.SubscriptionChanges;
import com.cobre.notifications.subscriptions.domain.ClientId;
import com.cobre.notifications.subscriptions.domain.EventTypes;
import com.cobre.notifications.subscriptions.domain.Subscription;
import com.cobre.notifications.subscriptions.domain.SubscriptionId;
import com.cobre.notifications.subscriptions.domain.WebhookUrl;

@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Import(JdbcSubscriptionRepository.class)
class JdbcSubscriptionRepositoryTests {

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("postgres:18.6"));

	private static final ClientId CLIENT_001 = new ClientId("CLIENT001");

	private static final ClientId CLIENT_002 = new ClientId("CLIENT002");

	private static final Instant CREATED_AT = Instant.parse("2026-10-28T12:00:00Z");

	@Autowired
	private JdbcSubscriptionRepository repository;

	@Autowired
	private JdbcClient jdbc;

	@Test
	void insertedSubscriptionIsReadBack() {
		Subscription subscription = subscription("S1", CLIENT_001, CREATED_AT, "credit_deposit", "debit_withdrawal");

		repository.insert(subscription, new byte[] { 1, 2, 3 });

		assertThat(repository.findById(CLIENT_001, subscription.id())).contains(subscription);
		assertThat(jdbc.sql("SELECT secret_ciphertext FROM subscriptions WHERE id = 'S1'").query(byte[].class).single())
			.containsExactly(1, 2, 3);
	}

	@Test
	void readsAreScopedToTheClient() {
		repository.insert(subscription("S1", CLIENT_001, CREATED_AT, "a"), new byte[] { 1 });
		repository.insert(subscription("S2", CLIENT_002, CREATED_AT, "a"), new byte[] { 1 });

		assertThat(repository.findById(CLIENT_001, new SubscriptionId("S2"))).isEmpty();
		assertThat(repository.findAll(CLIENT_001)).extracting(s -> s.id().value()).containsExactly("S1");
	}

	@Test
	void listIsNewestFirst() {
		repository.insert(subscription("S1", CLIENT_001, CREATED_AT, "a"), new byte[] { 1 });
		repository.insert(subscription("S2", CLIENT_001, CREATED_AT.plus(1, ChronoUnit.MINUTES), "a"), new byte[] { 1 });

		assertThat(repository.findAll(CLIENT_001)).extracting(s -> s.id().value()).containsExactly("S2", "S1");
	}

	@Test
	void updateChangesOnlyTheGivenFields() {
		repository.insert(subscription("S1", CLIENT_001, CREATED_AT, "a"), new byte[] { 1 });

		Subscription paused = repository
			.update(CLIENT_001, new SubscriptionId("S1"), new SubscriptionChanges(false, null))
			.orElseThrow();
		Subscription retyped = repository
			.update(CLIENT_001, new SubscriptionId("S1"), new SubscriptionChanges(null, new EventTypes(List.of("b", "c"))))
			.orElseThrow();

		assertThat(paused.active()).isFalse();
		assertThat(paused.eventTypes().values()).containsExactly("a");
		assertThat(retyped.active()).isFalse();
		assertThat(retyped.eventTypes().values()).containsExactly("b", "c");
		assertThat(retyped.url().value()).isEqualTo("https://hooks.example.org/S1");
	}

	@Test
	void updateOfAnotherClientsSubscriptionChangesNothing() {
		repository.insert(subscription("S1", CLIENT_002, CREATED_AT, "a"), new byte[] { 1 });

		assertThat(repository.update(CLIENT_001, new SubscriptionId("S1"), SubscriptionChanges.deactivation()))
			.isEmpty();
		assertThat(repository.findById(CLIENT_002, new SubscriptionId("S1")))
			.hasValueSatisfying(s -> assertThat(s.active()).isTrue());
	}

	private static Subscription subscription(String id, ClientId clientId, Instant createdAt, String... eventTypes) {
		return Subscription.create(new SubscriptionId(id), clientId, new WebhookUrl("https://hooks.example.org/" + id),
				new EventTypes(List.of(eventTypes)), createdAt);
	}

}
