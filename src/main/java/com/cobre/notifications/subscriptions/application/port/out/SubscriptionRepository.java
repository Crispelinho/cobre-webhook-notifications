package com.cobre.notifications.subscriptions.application.port.out;

import java.util.List;
import java.util.Optional;

import com.cobre.notifications.subscriptions.application.SubscriptionChanges;
import com.cobre.notifications.kernel.domain.ClientId;
import com.cobre.notifications.subscriptions.domain.Subscription;
import com.cobre.notifications.subscriptions.domain.SubscriptionId;

/**
 * Storage of subscriptions. Every read and write requires a {@link ClientId}, so no statement can run without a
 * client scope.
 */
public interface SubscriptionRepository {

	void insert(Subscription subscription, byte[] secretCiphertext);

	/**
	 * At most {@code limit} subscriptions, sorted by creation date descending, then by id descending.
	 */
	List<Subscription> findAll(ClientId clientId, int limit);

	long countActive(ClientId clientId);

	Optional<Subscription> findById(ClientId clientId, SubscriptionId id);

	/**
	 * Applies the changes in one statement and returns the updated subscription, or empty if the client has no
	 * subscription with this id.
	 */
	Optional<Subscription> update(ClientId clientId, SubscriptionId id, SubscriptionChanges changes);

	/**
	 * Inserts the subscription only if the client has fewer than {@code activeLimit} active subscriptions. The check and
	 * the insert run in one transaction, serialized per client, so concurrent requests cannot exceed the limit.
	 *
	 * @return {@code false} if the limit is already reached; nothing was stored
	 */
	boolean insertIfBelowLimit(Subscription subscription, byte[] secretCiphertext, int activeLimit);

	/**
	 * Applies the changes like {@link #update}, but a reactivation of an inactive subscription is refused when the client
	 * already has {@code activeLimit} active subscriptions. The check and the write run in one transaction.
	 *
	 * @throws com.cobre.notifications.subscriptions.domain.SubscriptionLimitReachedException if the limit blocks the change
	 */
	Optional<Subscription> updateWithinLimit(ClientId clientId, SubscriptionId id, SubscriptionChanges changes,
			int activeLimit);

}
