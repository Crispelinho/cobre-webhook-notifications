package com.cobre.notifications.shared.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;

import org.junit.jupiter.api.Test;

class WebhookSecretsTests {

	@Test
	void secretCarries256RandomBits() {
		String secret = WebhookSecrets.generate();

		assertThat(secret).startsWith(WebhookSecrets.PREFIX);
		assertThat(Base64.getUrlDecoder().decode(secret.substring(WebhookSecrets.PREFIX.length()))).hasSize(32);
	}

	@Test
	void secretsAreUnique() {
		assertThat(WebhookSecrets.generate()).isNotEqualTo(WebhookSecrets.generate());
	}

}
