package com.cobre.notifications.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import com.cobre.notifications.TestJwtIssuer;

/**
 * Token validation rules behind OWASP API2 (Broken Authentication).
 */
class JwtDecoderTests {

	private final TestJwtIssuer issuer = TestJwtIssuer.trusted();

	private final JwtDecoder decoder = SecurityConfig.jwtDecoder(issuer.publicKey(), TestJwtIssuer.ISSUER,
			TestJwtIssuer.AUDIENCE);

	@Test
	void acceptsAValidTokenAndExposesTheClientId() {
		var jwt = decoder.decode(issuer.token("CLIENT001", "notifications:read"));

		assertThat(jwt.getClaimAsString(SecurityConfig.CLIENT_ID_CLAIM)).isEqualTo("CLIENT001");
	}

	@Test
	void rejectsAnExpiredToken() {
		String token = issuer.token(claims -> claims.claim("client_id", "CLIENT001")
			.issuedAt(Instant.now().minusSeconds(7200))
			.expiresAt(Instant.now().minusSeconds(300)));

		assertThatExceptionOfType(JwtException.class).isThrownBy(() -> decoder.decode(token));
	}

	@Test
	void rejectsATokenSignedWithAnUnknownKey() {
		String token = TestJwtIssuer.untrusted().token("CLIENT001", "notifications:read");

		assertThatExceptionOfType(JwtException.class).isThrownBy(() -> decoder.decode(token));
	}

	@Test
	void rejectsATokenWithoutClientId() {
		String token = issuer.token(claims -> claims.claim("scope", "notifications:read"));

		assertThatExceptionOfType(JwtException.class).isThrownBy(() -> decoder.decode(token));
	}

	@Test
	void rejectsATokenFromAnotherIssuer() {
		String token = issuer.token(claims -> claims.claim("client_id", "CLIENT001").issuer("someone-else"));

		assertThatExceptionOfType(JwtException.class).isThrownBy(() -> decoder.decode(token));
	}

	@Test
	void rejectsATokenForAnotherAudience() {
		String token = issuer
			.token(claims -> claims.claim("client_id", "CLIENT001").audience(List.of("another-service")));

		assertThatExceptionOfType(JwtException.class).isThrownBy(() -> decoder.decode(token));
	}

}
