package com.cobre.notifications.subscriptions.application.port.out;

import com.cobre.notifications.subscriptions.application.IssuedSecret;
import com.cobre.notifications.subscriptions.domain.SubscriptionId;

/**
 * Creates signing secrets and encrypts them for storage, bound to the subscription that owns them.
 */
public interface SecretIssuer {

	IssuedSecret issue(SubscriptionId owner);

}
