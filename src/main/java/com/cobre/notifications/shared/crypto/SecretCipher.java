package com.cobre.notifications.shared.crypto;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encrypts webhook secrets at rest with AES-256-GCM. Each encryption uses a fresh random 96-bit nonce, stored in
 * front of the ciphertext: {@code nonce (12 bytes) || ciphertext || tag (16 bytes)}. Tampered or foreign ciphertexts
 * fail to decrypt instead of returning garbage.
 */
public final class SecretCipher {

	private static final String TRANSFORMATION = "AES/GCM/NoPadding";

	private static final int KEY_LENGTH_BYTES = 32;

	private static final int NONCE_LENGTH_BYTES = 12;

	private static final int TAG_LENGTH_BITS = 128;

	private final SecretKeySpec key;

	private final SecureRandom random = new SecureRandom();

	private SecretCipher(byte[] key) {
		if (key.length != KEY_LENGTH_BYTES) {
			throw new IllegalArgumentException("the webhook secrets key must be 256 bits");
		}
		this.key = new SecretKeySpec(key, "AES");
	}

	/**
	 * Cipher for a base64-encoded 256-bit key, as configured in {@code WEBHOOK_SECRETS_KEY}.
	 */
	public static SecretCipher fromBase64Key(String base64Key) {
		if (base64Key == null || base64Key.isBlank()) {
			throw new IllegalArgumentException("the webhook secrets key is required");
		}
		byte[] key;
		try {
			key = Base64.getDecoder().decode(base64Key.strip());
		}
		catch (IllegalArgumentException ex) {
			// The decoder message could echo part of the key.
			throw new IllegalArgumentException("the webhook secrets key must be base64");
		}
		try {
			return new SecretCipher(key);
		}
		finally {
			Arrays.fill(key, (byte) 0);
		}
	}

	public byte[] encrypt(byte[] plaintext) {
		byte[] nonce = new byte[NONCE_LENGTH_BYTES];
		random.nextBytes(nonce);
		try {
			Cipher cipher = Cipher.getInstance(TRANSFORMATION);
			cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
			byte[] ciphertext = cipher.doFinal(plaintext);
			return ByteBuffer.allocate(nonce.length + ciphertext.length).put(nonce).put(ciphertext).array();
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException("could not encrypt the secret", ex);
		}
	}

	/**
	 * @throws IllegalArgumentException if the ciphertext was not produced with this key or was modified
	 */
	public byte[] decrypt(byte[] encrypted) {
		if (encrypted == null || encrypted.length < NONCE_LENGTH_BYTES + TAG_LENGTH_BITS / 8) {
			throw new IllegalArgumentException("the encrypted secret is too short");
		}
		try {
			Cipher cipher = Cipher.getInstance(TRANSFORMATION);
			cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, encrypted, 0, NONCE_LENGTH_BYTES));
			return cipher.doFinal(encrypted, NONCE_LENGTH_BYTES, encrypted.length - NONCE_LENGTH_BYTES);
		}
		catch (AEADBadTagException ex) {
			throw new IllegalArgumentException("the encrypted secret is not valid for this key");
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException("could not decrypt the secret", ex);
		}
	}

}
