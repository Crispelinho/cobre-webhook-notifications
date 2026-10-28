package com.cobre.notifications.notifications;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.cobre.notifications.notifications.application.NotificationQueryService;
import com.cobre.notifications.notifications.application.port.out.NotificationQueryPort;

/**
 * Composition root of the notifications capability: wires framework-free use cases to their adapters.
 */
@Configuration(proxyBeanMethods = false)
class NotificationsConfiguration {

	@Bean
	NotificationQueryService notificationQueryService(NotificationQueryPort notificationQueryPort) {
		return new NotificationQueryService(notificationQueryPort);
	}

}
