package com.cobre.notifications.notifications.application.port.out;

import java.util.Optional;

import com.cobre.notifications.notifications.application.Page;
import com.cobre.notifications.notifications.application.PageQuery;
import com.cobre.notifications.kernel.domain.ClientId;
import com.cobre.notifications.notifications.domain.NotificationEvent;
import com.cobre.notifications.notifications.domain.NotificationEventId;
import com.cobre.notifications.notifications.domain.NotificationFilter;

/**
 * Read access to notification events. Every method requires a {@link ClientId}, so no query can run without a client
 * scope.
 */
public interface NotificationQueryPort {

	/**
	 * Sorted by creation date descending, then by id descending.
	 */
	Page<NotificationEvent> findPage(ClientId clientId, NotificationFilter filter, PageQuery pageQuery);

	Optional<NotificationEvent> findById(ClientId clientId, NotificationEventId id);

}
