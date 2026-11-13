package com.cobre.notifications.shared.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Signs webhook bodies so receivers can verify the sender (D24). The header value is
 * {@code t=<unix seconds>,v1=<hex>}, where {@code v1} is the lowercase hex HMAC-SHA256 of {@code "<t>.<body>"} keyed
 * with the UTF-8 bytes of the subscription secret. Receivers should recompute it, compare in constant time and reject
 * timestamps older than five minutes.
 */
public final class WebhookSigner {

	public static final String SIGNATURE_HEADER = "Cobre-Signature";

	private static final String ALGORITHM = "HmacSHA256";

	/**
	 * Value for the {@value #SIGNATURE_HEADER} header.
	 * @param secret the subscription secret, as returned to the client at creation
	 * @param timestampSeconds the send time in Unix seconds; the same value goes into the header
	 * @param body the exact bytes sent as the request body
	 */
	public String sign(String secret, long timestampSeconds, byte[] body) {
		try {
			Mac mac = Mac.getInstance(ALGORITHM);
			mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
			mac.update((timestampSeconds + ".").getBytes(StandardCharsets.UTF_8));
			byte[] signature = mac.doFinal(body);
			return "t=" + timestampSeconds + ",v1=" + HexFormat.of().formatHex(signature);
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException("could not sign the webhook", ex);
		}
	}

}
