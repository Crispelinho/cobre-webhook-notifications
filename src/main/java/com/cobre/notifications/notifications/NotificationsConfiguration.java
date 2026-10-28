package com.cobre.notifications.notifications;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.cobre.notifications.notifications.application.NotificationQueryService;
import com.cobre.notifications.notifications.application.RelayOutbox;
import com.cobre.notifications.notifications.application.port.out.DispatchPublisher;
import com.cobre.notifications.notifications.application.port.out.NotificationQueryPort;
import com.cobre.notifications.notifications.application.port.out.OutboxPort;

/**
 * Composition root of the notifications capability: wires framework-free use cases to their adapters.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class NotificationsConfiguration {

	@Bean
	NotificationQueryService notificationQueryService(NotificationQueryPort notificationQueryPort) {
		return new NotificationQueryService(notificationQueryPort);
	}

	@Bean
	RelayOutbox relayOutbox(OutboxPort outbox, DispatchPublisher publisher,
			@Value("${app.outbox.relay.batch-size:100}") int batchSize) {
		return new RelayOutbox(outbox, publisher, batchSize);
	}

}
