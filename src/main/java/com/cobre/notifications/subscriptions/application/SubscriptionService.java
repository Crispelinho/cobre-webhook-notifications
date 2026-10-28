package com.cobre.notifications.subscriptions.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

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
import com.cobre.notifications.subscriptions.domain.Subscription;
import com.cobre.notifications.subscriptions.domain.SubscriptionId;
import com.cobre.notifications.subscriptions.domain.WebhookUrl;

public class SubscriptionService
		implements CreateSubscription, ListSubscriptions, GetSubscription, UpdateSubscription, DeactivateSubscription {

	private final SubscriptionRepository repository;

	private final DestinationValidator destinationValidator;

	private final SecretIssuer secretIssuer;

	private final Clock clock;

	public SubscriptionService(SubscriptionRepository repository, DestinationValidator destinationValidator,
			SecretIssuer secretIssuer, Clock clock) {
		this.repository = Objects.requireNonNull(repository);
		this.destinationValidator = Objects.requireNonNull(destinationValidator);
		this.secretIssuer = Objects.requireNonNull(secretIssuer);
		this.clock = Objects.requireNonNull(clock);
	}

	@Override
	public CreatedSubscription create(ClientId clientId, WebhookUrl url, EventTypes eventTypes) {
		Objects.requireNonNull(clientId);
		Objects.requireNonNull(eventTypes);
		// Checked before anything is generated or stored.
		destinationValidator.validate(Objects.requireNonNull(url));
		Instant now = clock.instant();
		Subscription subscription = Subscription.create(SubscriptionId.generate(now), clientId, url, eventTypes, now);
		IssuedSecret secret = secretIssuer.issue();
		repository.insert(subscription, secret.ciphertext());
		return new CreatedSubscription(subscription, secret.value());
	}

	@Override
	public List<Subscription> list(ClientId clientId) {
		return repository.findAll(Objects.requireNonNull(clientId));
	}

	@Override
	public Optional<Subscription> get(ClientId clientId, SubscriptionId id) {
		return repository.findById(Objects.requireNonNull(clientId), Objects.requireNonNull(id));
	}

	@Override
	public Optional<Subscription> update(ClientId clientId, SubscriptionId id, SubscriptionChanges changes) {
		return repository.update(Objects.requireNonNull(clientId), Objects.requireNonNull(id),
				Objects.requireNonNull(changes));
	}

	@Override
	public boolean deactivate(ClientId clientId, SubscriptionId id) {
		return update(clientId, id, SubscriptionChanges.deactivation()).isPresent();
	}

}
