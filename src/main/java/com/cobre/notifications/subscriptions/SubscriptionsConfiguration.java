package com.cobre.notifications.subscriptions;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.cobre.notifications.audit.application.AuditLog;
import com.cobre.notifications.subscriptions.application.SubscriptionService;
import com.cobre.notifications.subscriptions.application.port.out.DestinationValidator;
import com.cobre.notifications.subscriptions.application.port.out.SecretIssuer;
import com.cobre.notifications.subscriptions.application.port.out.SubscriptionRepository;

/**
 * Composition root of the subscriptions capability: wires framework-free use cases to their adapters.
 */
@Configuration(proxyBeanMethods = false)
class SubscriptionsConfiguration {

	@Bean
	SubscriptionService subscriptionService(SubscriptionRepository subscriptionRepository,
			DestinationValidator destinationValidator, SecretIssuer secretIssuer, AuditLog auditLog) {
		return new SubscriptionService(subscriptionRepository, destinationValidator, secretIssuer, Clock.systemUTC(), auditLog);
	}

}
