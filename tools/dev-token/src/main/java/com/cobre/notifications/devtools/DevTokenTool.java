package com.cobre.notifications.devtools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.List;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * Development-only token issuer. Not part of the application artifact.
 *
 * <p>
 * On first run it creates an RSA key pair under {@code .local/dev-jwt/} (git-ignored). The application reads
 * {@code public.pem} from there by default. Usage: {@code ./gradlew devToken --args="CLIENT001 notifications:read"}.
 */
public final class DevTokenTool {

	private static final Path KEY_DIR = Path.of(".local", "dev-jwt");

	private static final Path PRIVATE_KEY = KEY_DIR.resolve("private.pem");

	private static final Path PUBLIC_KEY = KEY_DIR.resolve("public.pem");

	private static final String ISSUER = "cobre-notifications-dev";

	private static final String AUDIENCE = "notification-service";

	private DevTokenTool() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length == 0) {
			System.err.println("Usage: devToken <client_id> [scope ...]   (default scope: notifications:read)");
			System.exit(1);
		}
		String clientId = args[0];
		List<String> scopes = args.length > 1 ? Arrays.asList(args).subList(1, args.length)
				: List.of("notifications:read");

		RSAPrivateKey privateKey = loadOrCreatePrivateKey();
		Instant now = Instant.now();
		JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer(ISSUER)
			.audience(AUDIENCE)
			.subject(clientId)
			.claim("client_id", clientId)
			.claim("scope", String.join(" ", scopes))
			.issueTime(Date.from(now))
			.expirationTime(Date.from(now.plus(Duration.ofHours(12))))
			.build();
		SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
		jwt.sign(new RSASSASigner(privateKey));
		System.out.println(jwt.serialize());
	}

	private static RSAPrivateKey loadOrCreatePrivateKey() throws Exception {
		if (!Files.exists(PRIVATE_KEY)) {
			KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
			generator.initialize(2048);
			KeyPair keyPair = generator.generateKeyPair();
			Files.createDirectories(KEY_DIR);
			Files.writeString(PRIVATE_KEY, pem("PRIVATE KEY", keyPair.getPrivate().getEncoded()));
			Files.writeString(PUBLIC_KEY, pem("PUBLIC KEY", keyPair.getPublic().getEncoded()));
			System.err.println("Created development key pair in " + KEY_DIR.toAbsolutePath());
		}
		String body = Files.readString(PRIVATE_KEY).replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
		return (RSAPrivateKey) KeyFactory.getInstance("RSA")
			.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(body)));
	}

	private static String pem(String type, byte[] der) {
		String body = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der);
		return "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n";
	}

}
