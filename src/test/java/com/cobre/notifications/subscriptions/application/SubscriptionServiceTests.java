package com.cobre.notifications.subscriptions.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.cobre.notifications.audit.application.AuditEntry;
import com.cobre.notifications.audit.application.AuditOutcome;
import com.cobre.notifications.subscriptions.application.port.out.SubscriptionRepository;
import com.cobre.notifications.subscriptions.domain.ClientId;
import com.cobre.notifications.subscriptions.domain.EventTypes;
import com.cobre.notifications.subscriptions.domain.InvalidSubscriptionException;
import com.cobre.notifications.subscriptions.domain.Subscription;
import com.cobre.notifications.subscriptions.domain.SubscriptionId;
import com.cobre.notifications.subscriptions.domain.WebhookUrl;
import com.cobre.notifications.support.RecordingAuditLog;

class SubscriptionServiceTests {

	private static final ClientId CLIENT_001 = new ClientId("CLIENT001");

	private static final ClientId CLIENT_002 = new ClientId("CLIENT002");

	private static final WebhookUrl SAFE_URL = new WebhookUrl("https://hooks.example.org/cobre");

	private static final WebhookUrl UNSAFE_URL = new WebhookUrl("https://internal.example.org/");

	private static final EventTypes CREDIT_DEPOSIT = new EventTypes(List.of("credit_deposit"));

	private final InMemoryRepository repository = new InMemoryRepository();

	private final RecordingAuditLog audit = new RecordingAuditLog();

	private final SubscriptionService service = new SubscriptionService(repository, url -> {
		if (url.equals(UNSAFE_URL)) {
			throw new InvalidSubscriptionException("url is not an allowed destination");
		}
	}, owner -> new IssuedSecret("whsec_plain", new byte[] { 9, 9, 9 }),
			Clock.fixed(Instant.parse("2026-10-28T12:00:00Z"), ZoneOffset.UTC), audit);

	@Test
	void creationIsAuditedOnceWithTheNewSubscriptionId() {
		Subscription created = service.create(CLIENT_001, SAFE_URL, CREDIT_DEPOSIT).subscription();

		assertThat(audit.entries).containsExactly(new AuditEntry("CLIENT001", "subscription.create",
				created.id().value(), AuditOutcome.SUCCESS));
	}

	@Test
	void aRejectedCreationIsAuditedAsRejected() {
		assertThatExceptionOfType(InvalidSubscriptionException.class)
			.isThrownBy(() -> service.create(CLIENT_001, UNSAFE_URL, CREDIT_DEPOSIT));

		assertThat(audit.entries).containsExactly(
				new AuditEntry("CLIENT001", "subscription.create", "none", AuditOutcome.REJECTED));
	}

	@Test
	void updateIsAuditedOnce() {
		Subscription created = service.create(CLIENT_001, SAFE_URL, CREDIT_DEPOSIT).subscription();
		audit.entries.clear();

		service.update(CLIENT_001, created.id(), new SubscriptionChanges(null, CREDIT_DEPOSIT));

		assertThat(audit.entries).containsExactly(new AuditEntry("CLIENT001", "subscription.update",
				created.id().value(), AuditOutcome.SUCCESS));
	}

	@Test
	void deactivationIsAuditedOnceAndNotAlsoAsAnUpdate() {
		Subscription created = service.create(CLIENT_001, SAFE_URL, CREDIT_DEPOSIT).subscription();
		audit.entries.clear();

		service.deactivate(CLIENT_001, created.id());

		assertThat(audit.entries).containsExactly(new AuditEntry("CLIENT001", "subscription.deactivate",
				created.id().value(), AuditOutcome.SUCCESS));
	}

	@Test
	void changingASubscriptionOfAnotherClientIsAuditedAsNotFound() {
		Subscription created = service.create(CLIENT_001, SAFE_URL, CREDIT_DEPOSIT).subscription();
		audit.entries.clear();

		service.deactivate(CLIENT_002, created.id());
		service.update(CLIENT_002, created.id(), new SubscriptionChanges(null, CREDIT_DEPOSIT));

		assertThat(audit.entries).containsExactly(
				new AuditEntry("CLIENT002", "subscription.deactivate", created.id().value(), AuditOutcome.NOT_FOUND),
				new AuditEntry("CLIENT002", "subscription.update", created.id().value(), AuditOutcome.NOT_FOUND));
	}

	@Test
	void createdSubscriptionIsActiveOwnedByTheClientAndStoresOnlyTheCiphertext() {
		CreatedSubscription created = service.create(CLIENT_001, SAFE_URL, CREDIT_DEPOSIT);

		assertThat(created.secret()).isEqualTo("whsec_plain");
		assertThat(created.subscription().active()).isTrue();
		assertThat(created.subscription().clientId()).isEqualTo(CLIENT_001);
		assertThat(created.subscription().createdAt()).isEqualTo(Instant.parse("2026-10-28T12:00:00Z"));
		assertThat(repository.ciphertexts.get(created.subscription().id())).containsExactly(9, 9, 9);
	}

	@Test
	void unsafeDestinationStoresNothing() {
		assertThatExceptionOfType(InvalidSubscriptionException.class)
			.isThrownBy(() -> service.create(CLIENT_001, UNSAFE_URL, CREDIT_DEPOSIT));

		assertThat(repository.subscriptions).isEmpty();
	}

	@Test
	void listAndGetAreScopedToTheClient() {
		Subscription own = service.create(CLIENT_001, SAFE_URL, CREDIT_DEPOSIT).subscription();
		Subscription foreign = service.create(CLIENT_002, SAFE_URL, CREDIT_DEPOSIT).subscription();

		assertThat(service.list(CLIENT_001)).containsExactly(own);
		assertThat(service.get(CLIENT_001, foreign.id())).isEmpty();
		assertThat(service.get(CLIENT_001, own.id())).contains(own);
	}

	@Test
	void deactivationKeepsTheSubscriptionAndMarksItInactive() {
		Subscription own = service.create(CLIENT_001, SAFE_URL, CREDIT_DEPOSIT).subscription();

		assertThat(service.deactivate(CLIENT_001, own.id())).isTrue();
		assertThat(service.get(CLIENT_001, own.id())).hasValueSatisfying(s -> assertThat(s.active()).isFalse());
	}

	@Test
	void anotherClientCannotDeactivateOrUpdate() {
		Subscription foreign = service.create(CLIENT_002, SAFE_URL, CREDIT_DEPOSIT).subscription();

		assertThat(service.deactivate(CLIENT_001, foreign.id())).isFalse();
		assertThat(service.update(CLIENT_001, foreign.id(), new SubscriptionChanges(false, null))).isEmpty();
		assertThat(service.get(CLIENT_002, foreign.id())).hasValueSatisfying(s -> assertThat(s.active()).isTrue());
	}

	@Test
	void updateChangesOnlyTheGivenFields() {
		Subscription own = service.create(CLIENT_001, SAFE_URL, CREDIT_DEPOSIT).subscription();
		EventTypes newTypes = new EventTypes(List.of("debit_withdrawal"));

		Subscription updated = service.update(CLIENT_001, own.id(), new SubscriptionChanges(null, newTypes))
			.orElseThrow();

		assertThat(updated.eventTypes()).isEqualTo(newTypes);
		assertThat(updated.active()).isTrue();
		assertThat(updated.url()).isEqualTo(SAFE_URL);
	}

	@Test
	void activeSubscriptionsPerClientAreCapped() {
		for (int i = 0; i < SubscriptionService.MAX_ACTIVE_PER_CLIENT; i++) {
			service.create(CLIENT_001, SAFE_URL, CREDIT_DEPOSIT);
		}

		assertThatExceptionOfType(InvalidSubscriptionException.class)
			.isThrownBy(() -> service.create(CLIENT_001, SAFE_URL, CREDIT_DEPOSIT))
			.withMessageContaining("maximum");
		assertThat(service.create(CLIENT_002, SAFE_URL, CREDIT_DEPOSIT).subscription().clientId()).isEqualTo(CLIENT_002);
	}

	@Test
	void deactivatedSubscriptionsFreeCapacity() {
		Subscription first = service.create(CLIENT_001, SAFE_URL, CREDIT_DEPOSIT).subscription();
		for (int i = 1; i < SubscriptionService.MAX_ACTIVE_PER_CLIENT; i++) {
			service.create(CLIENT_001, SAFE_URL, CREDIT_DEPOSIT);
		}

		service.deactivate(CLIENT_001, first.id());

		assertThat(service.create(CLIENT_001, SAFE_URL, CREDIT_DEPOSIT).subscription().active()).isTrue();
	}

	@Test
	void secretIsIssuedForTheNewSubscription() {
		List<SubscriptionId> owners = new ArrayList<>();
		SubscriptionService service = new SubscriptionService(repository, url -> {
		}, owner -> {
			owners.add(owner);
			return new IssuedSecret("whsec_plain", new byte[] { 1 });
		}, Clock.systemUTC(), new RecordingAuditLog());

		Subscription created = service.create(CLIENT_001, SAFE_URL, CREDIT_DEPOSIT).subscription();

		assertThat(owners).containsExactly(created.id());
	}

	@Test
	void emptyChangesAreRejected() {
		assertThatExceptionOfType(InvalidSubscriptionException.class)
			.isThrownBy(() -> new SubscriptionChanges(null, null));
	}

	@Test
	void secretIsHiddenFromToString() {
		CreatedSubscription created = service.create(CLIENT_001, SAFE_URL, CREDIT_DEPOSIT);

		assertThat(created.toString()).doesNotContain("whsec_plain");
		assertThat(new IssuedSecret("whsec_plain", new byte[0]).toString()).doesNotContain("whsec_plain");
	}

	private static final class InMemoryRepository implements SubscriptionRepository {

		private final Map<SubscriptionId, Subscription> subscriptions = new LinkedHashMap<>();

		private final Map<SubscriptionId, byte[]> ciphertexts = new LinkedHashMap<>();

		@Override
		public void insert(Subscription subscription, byte[] secretCiphertext) {
			subscriptions.put(subscription.id(), subscription);
			ciphertexts.put(subscription.id(), secretCiphertext);
		}

		@Override
		public List<Subscription> findAll(ClientId clientId, int limit) {
			List<Subscription> result = new ArrayList<>(
					subscriptions.values().stream().filter(s -> s.clientId().equals(clientId)).toList());
			result.sort(Comparator.comparing(Subscription::createdAt).reversed());
			return result.stream().limit(limit).toList();
		}

		@Override
		public long countActive(ClientId clientId) {
			return subscriptions.values().stream().filter(s -> s.clientId().equals(clientId) && s.active()).count();
		}

		@Override
		public Optional<Subscription> findById(ClientId clientId, SubscriptionId id) {
			return Optional.ofNullable(subscriptions.get(id)).filter(s -> s.clientId().equals(clientId));
		}

		@Override
		public Optional<Subscription> update(ClientId clientId, SubscriptionId id, SubscriptionChanges changes) {
			return findById(clientId, id).map(s -> {
				Subscription updated = new Subscription(s.id(), s.clientId(), s.url(),
						changes.eventTypes() == null ? s.eventTypes() : changes.eventTypes(),
						changes.active() == null ? s.active() : changes.active(), s.createdAt());
				subscriptions.put(id, updated);
				return updated;
			});
		}

	}

}
