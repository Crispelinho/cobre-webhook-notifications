package com.cobre.notifications.shared.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.MediaType;

/**
 * Writes RFC 9457 bodies from security filters, which run before Spring MVC's exception handling. Only fixed text is
 * written, so no request data can be reflected into the response.
 */
final class ProblemResponses {

	private ProblemResponses() {
	}

	static void write(HttpServletResponse response, int status, String title, String detail) throws IOException {
		response.setStatus(status);
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		response.setCharacterEncoding(StandardCharsets.UTF_8);
		response.getWriter()
			.write("{\"type\":\"about:blank\",\"title\":\"%s\",\"status\":%d,\"detail\":\"%s\"}".formatted(title, status,
					detail));
	}

}
