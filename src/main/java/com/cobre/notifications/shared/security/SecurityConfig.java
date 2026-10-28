package com.cobre.notifications.shared.security;

import java.io.IOException;
import java.io.InputStream;
import java.security.interfaces.RSAPublicKey;
import java.util.List;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Stateless bearer-token security (OWASP API2): RS256 signature, expiry, issuer, audience and a mandatory
 * {@code client_id} claim. Each endpoint declares the scope it needs.
 */
@Configuration
@EnableConfigurationProperties(JwtProperties.class)
public class SecurityConfig {

	public static final String CLIENT_ID_CLAIM = "client_id";

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		http.csrf(csrf -> csrf.disable())
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.authorizeHttpRequests(auth -> auth
				.requestMatchers(HttpMethod.GET, "/notification_events", "/notification_events/*")
				.hasAuthority("SCOPE_notifications:read")
				.requestMatchers("/actuator/health", "/actuator/info")
				.permitAll()
				.anyRequest()
				.authenticated())
			.oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults())
				.authenticationEntryPoint(new ProblemDetailsAuthenticationEntryPoint())
				.accessDeniedHandler(new ProblemDetailsAccessDeniedHandler()))
			.exceptionHandling(exceptions -> exceptions
				.authenticationEntryPoint(new ProblemDetailsAuthenticationEntryPoint())
				.accessDeniedHandler(new ProblemDetailsAccessDeniedHandler()));
		return http.build();
	}

	@Bean
	JwtDecoder jwtDecoder(JwtProperties properties) throws IOException {
		try (InputStream pem = properties.publicKeyLocation().getInputStream()) {
			RSAPublicKey publicKey = RsaKeyConverters.x509().convert(pem);
			return jwtDecoder(publicKey, properties.issuer(), properties.audience());
		}
	}

	static JwtDecoder jwtDecoder(RSAPublicKey publicKey, String issuer, String audience) {
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(publicKey)
			.signatureAlgorithm(SignatureAlgorithm.RS256)
			.build();
		OAuth2TokenValidator<Jwt> audienceValidator = new JwtClaimValidator<List<String>>("aud",
				aud -> aud != null && aud.contains(audience));
		OAuth2TokenValidator<Jwt> clientIdValidator = new JwtClaimValidator<String>(CLIENT_ID_CLAIM,
				clientId -> clientId != null && !clientId.isBlank());
		decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer),
				audienceValidator, clientIdValidator));
		return decoder;
	}

}
