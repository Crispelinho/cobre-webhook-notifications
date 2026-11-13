package com.cobre.notifications.devtools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
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
 * On first run it creates an RSA key pair under {@code .local/dev-jwt/} (git-ignored). The {@code local} profile reads
 * {@code public.pem} from there. Usage: {@code ./gradlew devToken --args="CLIENT001 notifications:read"}.
 */
public final class DevTokenTool {

	/**
	 * Must match {@code app.security.jwt.issuer} in {@code application-local.yaml}.
	 */
	public static final String ISSUER = "cobre-notifications-dev";

	public static final String AUDIENCE = "notification-service";

	public static final Path DEFAULT_KEY_DIR = Path.of(".local", "dev-jwt");

	private DevTokenTool() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length == 0) {
			System.err.println("Usage: devToken <client_id> [scope ...]   (default scope: notifications:read)");
			System.exit(1);
		}
		List<String> scopes = args.length > 1 ? Arrays.asList(args).subList(1, args.length)
				: List.of("notifications:read");
		System.out.println(issue(DEFAULT_KEY_DIR, args[0], scopes));
	}

	/**
	 * Signs a 12-hour token for the client, creating the key pair in {@code keyDir} if it does not exist.
	 */
	public static String issue(Path keyDir, String clientId, List<String> scopes) throws Exception {
		RSAPrivateKey privateKey = loadOrCreatePrivateKey(keyDir);
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
		return jwt.serialize();
	}

	private static RSAPrivateKey loadOrCreatePrivateKey(Path keyDir) throws Exception {
		Path privateKey = keyDir.resolve("private.pem");
		if (!Files.exists(privateKey)) {
			KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
			generator.initialize(2048);
			KeyPair keyPair = generator.generateKeyPair();
			Files.createDirectories(keyDir);
			Files.writeString(privateKey, pem("PRIVATE KEY", keyPair.getPrivate().getEncoded()));
			restrictToOwner(privateKey);
			Files.writeString(keyDir.resolve("public.pem"), pem("PUBLIC KEY", keyPair.getPublic().getEncoded()));
			System.err.println("Created development key pair in " + keyDir.toAbsolutePath());
		}
		String body = Files.readString(privateKey).replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
		return (RSAPrivateKey) KeyFactory.getInstance("RSA")
			.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(body)));
	}

	private static void restrictToOwner(Path file) throws Exception {
		if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
			Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
		}
	}

	private static String pem(String type, byte[] der) {
		String body = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der);
		return "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n";
	}

}
