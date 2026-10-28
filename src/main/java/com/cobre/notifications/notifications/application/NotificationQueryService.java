package com.cobre.notifications.notifications.application;

import java.util.Objects;
import java.util.Optional;

import com.cobre.notifications.notifications.application.port.in.GetNotificationEvent;
import com.cobre.notifications.notifications.application.port.in.ListNotificationEvents;
import com.cobre.notifications.notifications.application.port.out.NotificationQueryPort;
import com.cobre.notifications.notifications.domain.ClientId;
import com.cobre.notifications.notifications.domain.NotificationEvent;
import com.cobre.notifications.notifications.domain.NotificationEventId;
import com.cobre.notifications.notifications.domain.NotificationFilter;

public class NotificationQueryService implements ListNotificationEvents, GetNotificationEvent {

	private final NotificationQueryPort queryPort;

	public NotificationQueryService(NotificationQueryPort queryPort) {
		this.queryPort = Objects.requireNonNull(queryPort);
	}

	@Override
	public Page<NotificationEvent> list(ClientId clientId, NotificationFilter filter, PageQuery pageQuery) {
		return queryPort.findPage(Objects.requireNonNull(clientId), Objects.requireNonNull(filter),
				Objects.requireNonNull(pageQuery));
	}

	@Override
	public Optional<NotificationEvent> get(ClientId clientId, NotificationEventId id) {
		return queryPort.findById(Objects.requireNonNull(clientId), Objects.requireNonNull(id));
	}

}
