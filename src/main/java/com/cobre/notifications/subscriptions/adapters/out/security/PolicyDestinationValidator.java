package com.cobre.notifications.subscriptions.adapters.out.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.cobre.notifications.shared.security.DestinationPolicy;
import com.cobre.notifications.shared.security.DestinationResolutionException;
import com.cobre.notifications.shared.security.RejectedDestinationException;
import com.cobre.notifications.subscriptions.application.port.out.DestinationValidator;
import com.cobre.notifications.subscriptions.domain.InvalidSubscriptionException;
import com.cobre.notifications.subscriptions.domain.WebhookUrl;

/**
 * Applies the shared {@link DestinationPolicy} at registration time. Callers get one generic answer for every
 * rejection, so the API cannot be used to learn how internal host names resolve; the reason is logged instead.
 */
@Component
class PolicyDestinationValidator implements DestinationValidator {

	private static final Logger log = LoggerFactory.getLogger(PolicyDestinationValidator.class);

	private final DestinationPolicy policy;

	PolicyDestinationValidator(DestinationPolicy policy) {
		this.policy = policy;
	}

	@Override
	public void validate(WebhookUrl url) {
		try {
			policy.check(url.value());
		}
		catch (RejectedDestinationException | DestinationResolutionException ex) {
			// The reason is fixed text; the URL is not logged because its path or query may carry tokens.
			log.info("Subscription destination rejected: {}", ex.getMessage());
			throw new InvalidSubscriptionException(
					"url is not an allowed destination: it must use https, be on an allowlisted host and resolve to public addresses");
		}
	}

}
