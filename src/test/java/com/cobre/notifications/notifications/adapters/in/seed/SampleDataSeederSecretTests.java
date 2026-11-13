package com.cobre.notifications.notifications.adapters.in.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.cobre.notifications.shared.crypto.SecretCipher;

import tools.jackson.databind.json.JsonMapper;

/**
 * The seed subscriptions are always signed: the secret comes from external configuration, never from SQL or the
 * repository, and a missing secret fails startup instead of falling back to an unsigned subscription.
 */
class SampleDataSeederSecretTests {

	private static final SecretCipher CIPHER = SecretCipher
		.fromBase64Key(Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes()));

	@Test
	void theSeedWithoutTheSecretFailsStartupWithAClearMessage() {
		// The system property wins over a DEMO_WEBHOOK_SECRET that may exist in the developer's environment.
		String previous = System.getProperty("DEMO_WEBHOOK_SECRET");
		System.setProperty("DEMO_WEBHOOK_SECRET", "");
		try {
			new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
				.withPropertyValues("spring.profiles.active=local", "app.seed.enabled=true")
				.withBean(JdbcClient.class, () -> mock(JdbcClient.class))
				.withBean(JsonMapper.class, () -> JsonMapper.builder().build())
				.withBean(SecretCipher.class, () -> CIPHER)
				.withBean(SampleDataSeeder.class)
				.run(context -> {
					assertThat(context).hasFailed();
					assertThat(context.getStartupFailure()).rootCause()
						.isInstanceOf(IllegalStateException.class)
						.hasMessageContaining("app.seed.secret")
						.hasMessageContaining("DEMO_WEBHOOK_SECRET");
				});
		}
		finally {
			if (previous == null) {
				System.clearProperty("DEMO_WEBHOOK_SECRET");
			}
			else {
				System.setProperty("DEMO_WEBHOOK_SECRET", previous);
			}
		}
	}

	@Test
	void aBlankOrMissingSecretIsRejectedByTheSeeder() {
		for (String secret : new String[] { null, "", "   " }) {
			assertThatThrownBy(() -> new SampleDataSeeder(null, JsonMapper.builder().build(), CIPHER, secret))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("app.seed.secret");
		}
	}

}
