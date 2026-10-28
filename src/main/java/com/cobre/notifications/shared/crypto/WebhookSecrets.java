package com.cobre.notifications.shared.crypto;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Generates per-subscription webhook secrets (D25): 256 random bits, encoded as URL-safe base64 with a {@code whsec_}
 * prefix so a leaked value is easy to recognize in logs and secret scanners.
 */
public final class WebhookSecrets {

	public static final String PREFIX = "whsec_";

	private static final int SECRET_LENGTH_BYTES = 32;

	private static final SecureRandom RANDOM = new SecureRandom();

	private WebhookSecrets() {
	}

	public static String generate() {
		byte[] bytes = new byte[SECRET_LENGTH_BYTES];
		RANDOM.nextBytes(bytes);
		return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

}
