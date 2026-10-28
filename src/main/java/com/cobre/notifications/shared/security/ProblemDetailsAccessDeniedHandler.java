package com.cobre.notifications.shared.security;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * Answers 403 with the RFC 6750 {@code insufficient_scope} error code and an RFC 9457 body, without describing which
 * scope is missing.
 */
class ProblemDetailsAccessDeniedHandler implements AccessDeniedHandler {

	@Override
	public void handle(HttpServletRequest request, HttpServletResponse response,
			AccessDeniedException accessDeniedException) throws IOException {
		response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"insufficient_scope\"");
		ProblemResponses.write(response, 403, "Forbidden", "The access token does not grant this operation.");
	}

}
