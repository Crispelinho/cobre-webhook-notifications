package com.cobre.notifications.shared.security;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.AuthenticationEntryPoint;

/**
 * Answers 401 with the standard {@code WWW-Authenticate} header and an RFC 9457 body. The body never includes the
 * validation failure, so callers learn nothing about why a token was rejected.
 */
class ProblemDetailsAuthenticationEntryPoint implements AuthenticationEntryPoint {

	private final BearerTokenAuthenticationEntryPoint bearer = new BearerTokenAuthenticationEntryPoint();

	@Override
	public void commence(HttpServletRequest request, HttpServletResponse response,
			AuthenticationException authException) throws IOException {
		bearer.commence(request, response, authException);
		ProblemResponses.write(response, 401, "Unauthorized", "A valid access token is required.");
	}

}
