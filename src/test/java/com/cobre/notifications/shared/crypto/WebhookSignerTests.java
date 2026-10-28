package com.cobre.notifications.shared.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class WebhookSignerTests {

	private final WebhookSigner signer = new WebhookSigner();

	/**
	 * Expected value computed independently: {@code printf '1700000000.{"a":1}' | openssl dgst -sha256 -hmac
	 * test-secret}.
	 */
	@Test
	void knownTestVector() {
		String header = signer.sign("test-secret", 1700000000L, "{\"a\":1}".getBytes(StandardCharsets.UTF_8));

		assertThat(header).isEqualTo("t=1700000000,v1=8cb2c3355fca388e9ac2caec004f4d5d7045d74937ab5faad61dc11682247a9f");
	}

	@Test
	void timestampIsPartOfTheSignedPayload() {
		byte[] body = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);

		String v1 = signer.sign("test-secret", 1700000000L, body).split(",v1=")[1];
		String v1Later = signer.sign("test-secret", 1700000001L, body).split(",v1=")[1];

		assertThat(v1).isNotEqualTo(v1Later);
	}

	@Test
	void headerNameMatchesTheContract() {
		assertThat(WebhookSigner.SIGNATURE_HEADER).isEqualTo("Cobre-Signature");
	}

}
