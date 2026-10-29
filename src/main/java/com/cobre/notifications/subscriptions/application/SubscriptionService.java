package com.cobre.notifications.subscriptions.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.cobre.notifications.audit.application.AuditEntry;
import com.cobre.notifications.audit.application.AuditLog;
import com.cobre.notifications.audit.application.AuditOutcome;
import com.cobre.notifications.subscriptions.application.port.in.CreateSubscription;
import com.cobre.notifications.subscriptions.application.port.in.DeactivateSubscription;
import com.cobre.notifications.subscriptions.application.port.in.GetSubscription;
import com.cobre.notifications.subscriptions.application.port.in.ListSubscriptions;
import com.cobre.notifications.subscriptions.application.port.in.UpdateSubscription;
import com.cobre.notifications.subscriptions.application.port.out.DestinationValidator;
import com.cobre.notifications.subscriptions.application.port.out.SecretIssuer;
import com.cobre.notifications.subscriptions.application.port.out.SubscriptionRepository;
import com.cobre.notifications.subscriptions.domain.ClientId;
import com.cobre.notifications.subscriptions.domain.EventTypes;
import com.cobre.notifications.subscriptions.domain.InvalidSubscriptionException;
import com.cobre.notifications.subscriptions.domain.Subscription;
import com.cobre.notifications.subscriptions.domain.SubscriptionId;
import com.cobre.notifications.subscriptions.domain.WebhookUrl;

public class SubscriptionService
		implements CreateSubscription, ListSubscriptions, GetSubscription, UpdateSubscription, DeactivateSubscription {

	/**
	 * Bounds the rows and DNS lookups one client can cause (OWASP API4). Deactivated subscriptions do not count.
	 */
	public static final int MAX_ACTIVE_PER_CLIENT = 50;

	/**
	 * Bounds the list response; deactivated subscriptions are kept, so the table only grows.
	 */
	public static final int LIST_LIMIT = 100;

	private static final String NO_RESOURCE = "none";

	private final SubscriptionRepository repository;

	private final DestinationValidator destinationValidator;

	private final SecretIssuer secretIssuer;

	private final Clock clock;

	private final AuditLog audit;

	public SubscriptionService(SubscriptionRepository repository, DestinationValidator destinationValidator,
			SecretIssuer secretIssuer, Clock clock, AuditLog audit) {
		this.repository = Objects.requireNonNull(repository);
		this.destinationValidator = Objects.requireNonNull(destinationValidator);
		this.secretIssuer = Objects.requireNonNull(secretIssuer);
		this.clock = Objects.requireNonNull(clock);
		this.audit = Objects.requireNonNull(audit);
	}

	@Override
	public CreatedSubscription create(ClientId clientId, WebhookUrl url, EventTypes eventTypes) {
		Objects.requireNonNull(clientId);
		Objects.requireNonNull(eventTypes);
		try {
			CreatedSubscription created = doCreate(clientId, url, eventTypes);
			audit(clientId, AuditEntry.SUBSCRIPTION_CREATE, created.subscription().id().value(), AuditOutcome.SUCCESS);
			return created;
		}
		catch (InvalidSubscriptionException ex) {
			// No subscription exists, so there is no resource ID to record.
			audit(clientId, AuditEntry.SUBSCRIPTION_CREATE, NO_RESOURCE, AuditOutcome.REJECTED);
			throw ex;
		}
	}

	private CreatedSubscription doCreate(ClientId clientId, WebhookUrl url, EventTypes eventTypes) {
		// Checked before anything is generated or stored. Two concurrent requests can both pass the limit check; the
		// limit is a guard against abuse, not an exact quota.
		if (repository.countActive(clientId) >= MAX_ACTIVE_PER_CLIENT) {
			throw new InvalidSubscriptionException(
					"the client already has the maximum of " + MAX_ACTIVE_PER_CLIENT + " active subscriptions");
		}
		destinationValidator.validate(Objects.requireNonNull(url));
		Instant now = clock.instant();
		Subscription subscription = Subscription.create(SubscriptionId.generate(now), clientId, url, eventTypes, now);
		IssuedSecret secret = secretIssuer.issue(subscription.id());
		repository.insert(subscription, secret.ciphertext());
		return new CreatedSubscription(subscription, secret.value());
	}

	@Override
	public List<Subscription> list(ClientId clientId) {
		return repository.findAll(Objects.requireNonNull(clientId), LIST_LIMIT);
	}

	@Override
	public Optional<Subscription> get(ClientId clientId, SubscriptionId id) {
		return repository.findById(Objects.requireNonNull(clientId), Objects.requireNonNull(id));
	}

	@Override
	public Optional<Subscription> update(ClientId clientId, SubscriptionId id, SubscriptionChanges changes) {
		Objects.requireNonNull(clientId);
		Objects.requireNonNull(id);
		Optional<Subscription> updated = repository.update(clientId, id, Objects.requireNonNull(changes));
		audit(clientId, AuditEntry.SUBSCRIPTION_UPDATE, id.value(),
				updated.isPresent() ? AuditOutcome.SUCCESS : AuditOutcome.NOT_FOUND);
		return updated;
	}

	@Override
	public boolean deactivate(ClientId clientId, SubscriptionId id) {
		Objects.requireNonNull(clientId);
		Objects.requireNonNull(id);
		// Straight to the repository: going through update() would write a second, misleading audit entry.
		boolean found = repository.update(clientId, id, SubscriptionChanges.deactivation()).isPresent();
		audit(clientId, AuditEntry.SUBSCRIPTION_DEACTIVATE, id.value(),
				found ? AuditOutcome.SUCCESS : AuditOutcome.NOT_FOUND);
		return found;
	}

	private void audit(ClientId clientId, String action, String resourceId, AuditOutcome outcome) {
		audit.record(new AuditEntry(clientId.value(), action, resourceId, outcome));
	}

}
