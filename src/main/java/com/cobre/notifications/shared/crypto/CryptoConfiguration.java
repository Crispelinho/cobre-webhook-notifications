package com.cobre.notifications.shared.crypto;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class CryptoConfiguration {

	/**
	 * Fails startup when {@code app.webhook.secrets-key} is missing or is not a base64 256-bit key.
	 */
	@Bean
	SecretCipher secretCipher(@Value("${app.webhook.secrets-key}") String secretsKey) {
		return SecretCipher.fromBase64Key(secretsKey);
	}

	@Bean
	WebhookSigner webhookSigner() {
		return new WebhookSigner();
	}

}
