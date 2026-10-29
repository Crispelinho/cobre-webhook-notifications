package com.cobre.notifications.notifications;

import java.time.Clock;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.cobre.notifications.audit.application.AuditLog;
import com.cobre.notifications.notifications.application.DeliveryService;
import com.cobre.notifications.notifications.application.NotificationQueryService;
import com.cobre.notifications.notifications.application.RecoverInterruptedDeliveries;
import com.cobre.notifications.notifications.application.RelayOutbox;
import com.cobre.notifications.notifications.application.ReplayNotificationEventUseCase;
import com.cobre.notifications.notifications.application.port.out.DeliveryMetrics;
import com.cobre.notifications.notifications.application.port.out.DeliveryStore;
import com.cobre.notifications.notifications.application.port.out.DispatchPublisher;
import com.cobre.notifications.notifications.application.port.out.LeaseRecoveryStore;
import com.cobre.notifications.notifications.application.port.out.NotificationQueryPort;
import com.cobre.notifications.notifications.application.port.out.OutboxPort;
import com.cobre.notifications.notifications.application.port.out.ReplayStore;
import com.cobre.notifications.notifications.application.port.out.WebhookSender;

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
	ReplayNotificationEventUseCase replayNotificationEvent(ReplayStore replayStore, AuditLog auditLog, Clock clock) {
		return new ReplayNotificationEventUseCase(replayStore, clock, auditLog);
	}

	@Bean
	RelayOutbox relayOutbox(OutboxPort outbox, DispatchPublisher publisher,
			@Value("${app.outbox.relay.batch-size:100}") int batchSize) {
		return new RelayOutbox(outbox, publisher, batchSize);
	}

	@Bean
	DeliveryService deliveryService(DeliveryStore store, WebhookSender sender,
			@Value("${app.delivery.retry.max-attempts:5}") int maxAttempts, DeliveryMetrics metrics, Clock clock,
			@Value("${app.delivery.lease.duration:PT60S}") Duration leaseDuration) {
		return new DeliveryService(store, sender, clock, maxAttempts, metrics, leaseDuration);
	}

	@Bean
	RecoverInterruptedDeliveries recoverInterruptedDeliveries(LeaseRecoveryStore store, Clock clock,
			@Value("${app.delivery.retry.max-attempts:5}") int maxAttempts,
			@Value("${app.delivery.lease.recovery.batch-size:100}") int batchSize) {
		return new RecoverInterruptedDeliveries(store, clock, maxAttempts, batchSize);
	}

}
