package com.cobre.notifications.shared.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encrypts webhook secrets at rest with AES-256-GCM (D25). Stored format:
 * {@code version (1 byte) || nonce (12 bytes) || ciphertext || tag (16 bytes)}, with a fresh random nonce per
 * encryption. The version byte leaves room for a key rotation without guessing the format.
 * <p>
 * The associated data binds a ciphertext to its owner (callers pass the subscription id): it is not stored, it must be
 * the same on decryption, and a ciphertext copied to another row fails to decrypt. Tampered, foreign or misbound
 * ciphertexts fail instead of returning garbage.
 */
public final class SecretCipher {

	private static final String TRANSFORMATION = "AES/GCM/NoPadding";

	private static final byte FORMAT_VERSION = 1;

	private static final int VERSION_LENGTH_BYTES = 1;

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

	/**
	 * @param associatedData the owner of the secret, such as the subscription id; required again to decrypt
	 */
	public byte[] encrypt(byte[] plaintext, String associatedData) {
		byte[] nonce = new byte[NONCE_LENGTH_BYTES];
		random.nextBytes(nonce);
		try {
			Cipher cipher = Cipher.getInstance(TRANSFORMATION);
			cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
			cipher.updateAAD(aad(FORMAT_VERSION, associatedData));
			byte[] ciphertext = cipher.doFinal(plaintext);
			return ByteBuffer.allocate(VERSION_LENGTH_BYTES + nonce.length + ciphertext.length)
				.put(FORMAT_VERSION)
				.put(nonce)
				.put(ciphertext)
				.array();
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException("could not encrypt the secret", ex);
		}
	}

	/**
	 * @param associatedData the same value given to {@link #encrypt(byte[], String)}
	 * @throws IllegalArgumentException if the ciphertext was not produced with this key and associated data, was
	 * modified, or uses an unknown format version
	 */
	public byte[] decrypt(byte[] encrypted, String associatedData) {
		if (encrypted == null || encrypted.length < VERSION_LENGTH_BYTES + NONCE_LENGTH_BYTES + TAG_LENGTH_BITS / 8) {
			throw new IllegalArgumentException("the encrypted secret is too short");
		}
		if (encrypted[0] != FORMAT_VERSION) {
			throw new IllegalArgumentException("the encrypted secret uses an unknown format version");
		}
		try {
			Cipher cipher = Cipher.getInstance(TRANSFORMATION);
			cipher.init(Cipher.DECRYPT_MODE, key,
					new GCMParameterSpec(TAG_LENGTH_BITS, encrypted, VERSION_LENGTH_BYTES, NONCE_LENGTH_BYTES));
			cipher.updateAAD(aad(encrypted[0], associatedData));
			int offset = VERSION_LENGTH_BYTES + NONCE_LENGTH_BYTES;
			return cipher.doFinal(encrypted, offset, encrypted.length - offset);
		}
		catch (AEADBadTagException ex) {
			throw new IllegalArgumentException("the encrypted secret is not valid for this key and owner");
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException("could not decrypt the secret", ex);
		}
	}

	/**
	 * The version byte is authenticated too, so it cannot be changed to downgrade the format.
	 */
	private static byte[] aad(byte version, String associatedData) {
		if (associatedData == null || associatedData.isEmpty()) {
			throw new IllegalArgumentException("associated data is required");
		}
		byte[] data = associatedData.getBytes(StandardCharsets.UTF_8);
		return ByteBuffer.allocate(VERSION_LENGTH_BYTES + data.length).put(version).put(data).array();
	}

}
