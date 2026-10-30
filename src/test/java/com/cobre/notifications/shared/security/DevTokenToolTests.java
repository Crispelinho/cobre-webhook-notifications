package com.cobre.notifications.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.interfaces.RSAPublicKey;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.converter.RsaKeyConverters;

import com.cobre.notifications.devtools.DevTokenTool;

/**
 * Scenario "Issue a token for local testing": the application's decoder accepts a token issued by the dev tool.
 */
class DevTokenToolTests {

	@Test
	void applicationAcceptsTokensIssuedByTheDevTool(@TempDir Path keyDir) throws Exception {
		String token = DevTokenTool.issue(keyDir, "CLIENT001", List.of("notifications:read"));

		RSAPublicKey publicKey;
		try (InputStream pem = Files.newInputStream(keyDir.resolve("public.pem"))) {
			publicKey = RsaKeyConverters.x509().convert(pem);
		}
		var jwt = SecurityConfig.jwtDecoder(publicKey, DevTokenTool.ISSUER, DevTokenTool.AUDIENCE).decode(token);

		assertThat(jwt.getClaimAsString(SecurityConfig.CLIENT_ID_CLAIM)).isEqualTo("CLIENT001");
		assertThat(jwt.getClaimAsString("scope")).isEqualTo("notifications:read");
	}

	@Test
	void reusesTheExistingKeyPair(@TempDir Path keyDir) throws Exception {
		DevTokenTool.issue(keyDir, "CLIENT001", List.of("notifications:read"));
		String firstPublicKey = Files.readString(keyDir.resolve("public.pem"));

		DevTokenTool.issue(keyDir, "CLIENT002", List.of("notifications:read"));

		assertThat(Files.readString(keyDir.resolve("public.pem"))).isEqualTo(firstPublicKey);
	}

}
