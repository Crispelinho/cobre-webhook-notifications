package com.cobre.notifications.subscriptions.application.port.out;

import com.cobre.notifications.subscriptions.application.IssuedSecret;

/**
 * Creates signing secrets and encrypts them for storage.
 */
public interface SecretIssuer {

	IssuedSecret issue();

}
