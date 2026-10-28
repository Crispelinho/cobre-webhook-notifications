package com.cobre.notifications.notifications.application.port.out;

import com.cobre.notifications.notifications.application.DispatchMessage;

/**
 * Access to the transactional outbox for the relay.
 */
public interface OutboxPort {

	/**
	 * Locks up to {@code limit} unpublished rows, oldest first, skipping rows another relay instance holds, and offers
	 * them to {@code handler} one at a time. A row is marked published only if the handler returns {@code true}; the
	 * first {@code false} ends the batch. Locking, handling and marking happen in one transaction.
	 *
	 * @return how many rows were marked published
	 */
	int relayBatch(int limit, Handler handler);

	@FunctionalInterface
	interface Handler {

		/**
		 * @return {@code true} if the message was confirmed by the transport and its row may be marked published
		 */
		boolean handle(DispatchMessage message);

	}

}
