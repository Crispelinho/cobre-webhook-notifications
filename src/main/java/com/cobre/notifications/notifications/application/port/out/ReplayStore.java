package com.cobre.notifications.notifications.application.port.out;

import java.time.Instant;
import java.util.Optional;

import com.cobre.notifications.kernel.domain.ClientId;
import com.cobre.notifications.notifications.domain.NotificationEvent;
import com.cobre.notifications.notifications.domain.NotificationEventId;

/**
 * Persistence for replays. Every lookup requires a {@link ClientId}, so a replay can never touch another client's
 * notification event.
 */
public interface ReplayStore {

	Optional<NotificationEvent> findById(ClientId clientId, NotificationEventId id);

	/**
	 * Reads the {@code active} flag of the subscription of this notification event from the database.
	 */
	boolean isSubscriptionActive(ClientId clientId, NotificationEventId id);

	/**
	 * Stores the new round and its outbox row in one transaction, conditional on the version and the {@code failed}
	 * status of {@code before}.
	 *
	 * @return {@code false} if the stored version or state no longer matches {@code before}; nothing was written
	 */
	boolean startReplay(NotificationEvent before, NotificationEvent after, Instant now);

}
