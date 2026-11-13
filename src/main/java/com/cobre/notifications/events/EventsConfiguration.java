package com.cobre.notifications.events;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.cobre.notifications.events.application.IngestEventUseCase;
import com.cobre.notifications.events.application.port.out.ActiveSubscriptionsPort;
import com.cobre.notifications.events.application.port.out.EventStorePort;
import com.cobre.notifications.events.application.port.out.NotificationIdGenerator;

/**
 * Composition root of the events capability: wires framework-free use cases to their adapters.
 */
@Configuration(proxyBeanMethods = false)
class EventsConfiguration {

	@Bean
	IngestEventUseCase ingestEventUseCase(ActiveSubscriptionsPort subscriptions, EventStorePort store,
			NotificationIdGenerator ids, Clock clock) {
		return new IngestEventUseCase(subscriptions, store, ids, clock);
	}

}
