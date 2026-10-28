package com.cobre.notifications.notifications.application.port.in;

import com.cobre.notifications.notifications.application.Page;
import com.cobre.notifications.notifications.application.PageQuery;
import com.cobre.notifications.notifications.domain.ClientId;
import com.cobre.notifications.notifications.domain.NotificationEvent;
import com.cobre.notifications.notifications.domain.NotificationFilter;

public interface ListNotificationEvents {

	Page<NotificationEvent> list(ClientId clientId, NotificationFilter filter, PageQuery pageQuery);

}
