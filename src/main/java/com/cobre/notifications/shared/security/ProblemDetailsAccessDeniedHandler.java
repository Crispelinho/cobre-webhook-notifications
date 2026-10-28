package com.cobre.notifications.shared.security;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.access.AccessDeniedHandler;

class ProblemDetailsAccessDeniedHandler implements AccessDeniedHandler {

	private final BearerTokenAccessDeniedHandler bearer = new BearerTokenAccessDeniedHandler();

	@Override
	public void handle(HttpServletRequest request, HttpServletResponse response,
			AccessDeniedException accessDeniedException) throws IOException {
		bearer.handle(request, response, accessDeniedException);
		ProblemResponses.write(response, 403, "Forbidden", "The access token does not grant this operation.");
	}

}
