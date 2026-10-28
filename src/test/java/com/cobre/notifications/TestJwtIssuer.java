package com.cobre.notifications;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.function.Consumer;

import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;

/**
 * Issues tokens for tests with an in-memory key pair. No private key is ever written to the repository.
 */
public final class TestJwtIssuer {

	public static final String ISSUER = "cobre-notifications-test";

	public static final String AUDIENCE = "notification-service";

	private static final TestJwtIssuer TRUSTED = new TestJwtIssuer();

	private static final TestJwtIssuer UNTRUSTED = new TestJwtIssuer();

	private final KeyPair keyPair;

	private final NimbusJwtEncoder encoder;

	private TestJwtIssuer() {
		try {
			KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
			generator.initialize(2048);
			this.keyPair = generator.generateKeyPair();
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
		RSAKey jwk = new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
			.privateKey((RSAPrivateKey) keyPair.getPrivate())
			.build();
		this.encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(jwk)));
	}

	/**
	 * Issuer whose public key the application under test trusts.
	 */
	public static TestJwtIssuer trusted() {
		return TRUSTED;
	}

	/**
	 * Issuer with a different key, to test forged signatures.
	 */
	public static TestJwtIssuer untrusted() {
		return UNTRUSTED;
	}

	public RSAPublicKey publicKey() {
		return (RSAPublicKey) keyPair.getPublic();
	}

	/**
	 * Valid token for the client with the given scopes.
	 */
	public String token(String clientId, String... scopes) {
		return token(claims -> claims.claim("client_id", clientId).claim("scope", String.join(" ", scopes)));
	}

	/**
	 * Token with valid defaults (issuer, audience, one-hour expiry) that the customizer can change or remove.
	 */
	public String token(Consumer<JwtClaimsSet.Builder> customizer) {
		Instant now = Instant.now();
		JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
			.issuer(ISSUER)
			.audience(List.of(AUDIENCE))
			.subject("test")
			.issuedAt(now)
			.expiresAt(now.plus(Duration.ofHours(1)));
		customizer.accept(claims);
		JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).build();
		return encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
	}

	/**
	 * Location of the trusted public key in PEM format, for {@code app.security.jwt.public-key-location}.
	 */
	public static String trustedPublicKeyLocation() {
		try {
			Path pem = Files.createTempFile("test-jwt-public", ".pem");
			pem.toFile().deleteOnExit();
			String body = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(TRUSTED.publicKey().getEncoded());
			Files.writeString(pem, "-----BEGIN PUBLIC KEY-----\n" + body + "\n-----END PUBLIC KEY-----\n");
			return pem.toUri().toString();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
