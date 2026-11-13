package com.cobre.notifications.shared.security;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

/**
 * Answers 401 with an RFC 9457 body. Unlike Spring's {@code BearerTokenAuthenticationEntryPoint}, the
 * {@code WWW-Authenticate} header carries no {@code error_description}, so callers learn nothing about why a token was
 * rejected (for example, that it expired or lacks a claim).
 */
class ProblemDetailsAuthenticationEntryPoint implements AuthenticationEntryPoint {

	@Override
	public void commence(HttpServletRequest request, HttpServletResponse response,
			AuthenticationException authException) throws IOException {
		response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
		ProblemResponses.write(response, 401, "Unauthorized", "A valid access token is required.");
	}

}
