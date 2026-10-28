package com.cobre.notifications.subscriptions.application.port.out;

import java.util.List;
import java.util.Optional;

import com.cobre.notifications.subscriptions.application.SubscriptionChanges;
import com.cobre.notifications.subscriptions.domain.ClientId;
import com.cobre.notifications.subscriptions.domain.Subscription;
import com.cobre.notifications.subscriptions.domain.SubscriptionId;

/**
 * Storage of subscriptions. Every read and write requires a {@link ClientId}, so no statement can run without a
 * client scope.
 */
public interface SubscriptionRepository {

	void insert(Subscription subscription, byte[] secretCiphertext);

	/**
	 * Sorted by creation date descending, then by id descending.
	 */
	List<Subscription> findAll(ClientId clientId);

	Optional<Subscription> findById(ClientId clientId, SubscriptionId id);

	/**
	 * Applies the changes in one statement and returns the updated subscription, or empty if the client has no
	 * subscription with this id.
	 */
	Optional<Subscription> update(ClientId clientId, SubscriptionId id, SubscriptionChanges changes);

}
