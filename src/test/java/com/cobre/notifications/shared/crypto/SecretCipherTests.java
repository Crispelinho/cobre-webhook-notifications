package com.cobre.notifications.shared.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import org.junit.jupiter.api.Test;

class SecretCipherTests {

	private final SecretCipher cipher = SecretCipher.fromBase64Key(randomKey());

	@Test
	void encryptedSecretDecryptsBackToTheOriginal() {
		byte[] secret = "whsec_example".getBytes(StandardCharsets.UTF_8);

		byte[] encrypted = cipher.encrypt(secret);

		assertThat(encrypted).isNotEqualTo(secret);
		assertThat(cipher.decrypt(encrypted)).isEqualTo(secret);
	}

	@Test
	void everyEncryptionUsesAFreshNonce() {
		byte[] secret = "whsec_example".getBytes(StandardCharsets.UTF_8);

		assertThat(cipher.encrypt(secret)).isNotEqualTo(cipher.encrypt(secret));
	}

	@Test
	void tamperedCiphertextIsRejected() {
		byte[] encrypted = cipher.encrypt("whsec_example".getBytes(StandardCharsets.UTF_8));
		encrypted[encrypted.length - 1] ^= 1;

		assertThatIllegalArgumentException().isThrownBy(() -> cipher.decrypt(encrypted));
	}

	@Test
	void ciphertextFromAnotherKeyIsRejected() {
		byte[] encrypted = SecretCipher.fromBase64Key(randomKey()).encrypt(new byte[] { 1, 2, 3 });

		assertThatIllegalArgumentException().isThrownBy(() -> cipher.decrypt(encrypted));
	}

	@Test
	void truncatedCiphertextIsRejected() {
		assertThatIllegalArgumentException().isThrownBy(() -> cipher.decrypt(new byte[10]));
	}

	@Test
	void keyMustBe256Bits() {
		String shortKey = Base64.getEncoder().encodeToString(new byte[16]);

		assertThatIllegalArgumentException().isThrownBy(() -> SecretCipher.fromBase64Key(shortKey))
			.withMessage("the webhook secrets key must be 256 bits");
	}

	@Test
	void keyIsRequired() {
		assertThatIllegalArgumentException().isThrownBy(() -> SecretCipher.fromBase64Key(" "));
	}

	@Test
	void invalidBase64KeyIsRejectedWithoutEchoingIt() {
		assertThatIllegalArgumentException().isThrownBy(() -> SecretCipher.fromBase64Key("not*base64*key"))
			.withMessage("the webhook secrets key must be base64");
	}

	private static String randomKey() {
		byte[] key = new byte[32];
		new SecureRandom().nextBytes(key);
		return Base64.getEncoder().encodeToString(key);
	}

}
