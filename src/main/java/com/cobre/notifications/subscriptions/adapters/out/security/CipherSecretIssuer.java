package com.cobre.notifications.subscriptions.adapters.out.security;

import java.nio.charset.StandardCharsets;

import org.springframework.stereotype.Component;

import com.cobre.notifications.shared.crypto.SecretCipher;
import com.cobre.notifications.shared.crypto.WebhookSecrets;
import com.cobre.notifications.subscriptions.application.IssuedSecret;
import com.cobre.notifications.subscriptions.application.port.out.SecretIssuer;

/**
 * Issues a {@link WebhookSecrets} secret and encrypts its UTF-8 bytes with the {@link SecretCipher}; delivery decrypts
 * the same bytes to key the webhook signature.
 */
@Component
class CipherSecretIssuer implements SecretIssuer {

	private final SecretCipher cipher;

	CipherSecretIssuer(SecretCipher cipher) {
		this.cipher = cipher;
	}

	@Override
	public IssuedSecret issue() {
		String secret = WebhookSecrets.generate();
		return new IssuedSecret(secret, cipher.encrypt(secret.getBytes(StandardCharsets.UTF_8)));
	}

}
