package com.cobre.notifications.notifications.application.port.in;

import java.util.Optional;

import com.cobre.notifications.kernel.domain.ClientId;
import com.cobre.notifications.notifications.domain.NotificationEvent;
import com.cobre.notifications.notifications.domain.NotificationEventId;

public interface GetNotificationEvent {

	/**
	 * Empty when the notification event does not exist or belongs to another client; callers cannot tell the two
	 * apart.
	 */
	Optional<NotificationEvent> get(ClientId clientId, NotificationEventId id);

}
