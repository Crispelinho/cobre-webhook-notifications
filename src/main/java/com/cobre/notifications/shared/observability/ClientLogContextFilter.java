package com.cobre.notifications.shared.observability;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.cobre.notifications.shared.security.SecurityConfig;

/**
 * Adds the authenticated client to the log context of the request. It runs after the security filter chain (which
 * has a higher precedence), so the access token is already validated; an unauthenticated request logs no client.
 */
@Component
class ClientLogContextFilter extends OncePerRequestFilter {

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String clientId = clientId();
		if (clientId == null) {
			chain.doFilter(request, response);
			return;
		}
		try (MDC.MDCCloseable ignored = MDC.putCloseable(LogContext.CLIENT_ID, clientId)) {
			chain.doFilter(request, response);
		}
	}

	private static String clientId() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
			return jwt.getClaimAsString(SecurityConfig.CLIENT_ID_CLAIM);
		}
		return null;
	}

}
