package com.cobre.notifications.notifications.application.port.in;

import java.util.Optional;

import com.cobre.notifications.notifications.domain.ClientId;
import com.cobre.notifications.notifications.domain.NotificationEvent;
import com.cobre.notifications.notifications.domain.NotificationEventId;

public interface ReplayNotificationEvent {

	/**
	 * Starts a new delivery round for a failed notification event of the client.
	 *
	 * @return the notification event in its new round (pending); empty when it does not exist or belongs to another
	 * client, so callers cannot tell the two apart
	 * @throws com.cobre.notifications.notifications.domain.NotReplayableException if the event is not failed, or a
	 * concurrent replay won
	 */
	Optional<NotificationEvent> replay(ClientId clientId, NotificationEventId id);

}
