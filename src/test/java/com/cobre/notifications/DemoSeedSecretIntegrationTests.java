package com.cobre.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.cobre.notifications.notifications.adapters.in.seed.SampleDataSeeder;
import com.cobre.notifications.shared.crypto.SecretCipher;

/**
 * The seed subscriptions are stored with a secret that comes from configuration, encrypted for their own id.
 */
@SpringBootTest(properties = "app.seed.enabled=true")
@Import(TestcontainersConfiguration.class)
class DemoSeedSecretIntegrationTests {

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private SecretCipher cipher;

	@Autowired
	private SampleDataSeeder seeder;

	@Value("${app.seed.secret}")
	private String configuredSecret;

	@Test
	void everySeedSubscriptionHasACiphertextThatDecryptsToTheConfiguredSecret() {
		List<String> ids = jdbc.sql("SELECT id FROM subscriptions ORDER BY id").query(String.class).list();

		assertThat(ids).containsExactly("SUB-CLIENT001", "SUB-CLIENT002", "SUB-CLIENT003");
		for (String id : ids) {
			byte[] ciphertext = ciphertext(id);
			assertThat(ciphertext).isNotNull();
			assertThat(new String(cipher.decrypt(ciphertext, id), StandardCharsets.UTF_8)).isEqualTo(configuredSecret);
		}
	}

	@Test
	void theCiphertextIsBoundToItsSubscription() {
		byte[] ciphertext = ciphertext("SUB-CLIENT001");

		assertThatThrownBy(() -> cipher.decrypt(ciphertext, "SUB-CLIENT002"))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void seedingAgainRepairsASubscriptionLeftWithoutASecret() {
		jdbc.sql("UPDATE subscriptions SET secret_ciphertext = NULL WHERE id = 'SUB-CLIENT003'").update();

		seeder.seed();

		assertThat(jdbc.sql("SELECT count(*) FROM subscriptions WHERE secret_ciphertext IS NULL")
			.query(Long.class)
			.single()).isZero();
		assertThat(new String(cipher.decrypt(ciphertext("SUB-CLIENT003"), "SUB-CLIENT003"), StandardCharsets.UTF_8))
			.isEqualTo(configuredSecret);
	}

	private byte[] ciphertext(String id) {
		return jdbc.sql("SELECT secret_ciphertext FROM subscriptions WHERE id = :id")
			.param("id", id)
			.query(byte[].class)
			.single();
	}

}
