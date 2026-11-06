package com.cobre.notifications.shared.security;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Per-client rate limit on the self-service API (OWASP API4, D29): at most {@code requests-per-minute} requests per
 * client in a minute, in memory (Redis would be the evolution for several instances, each instance counts alone
 * here). The bucket is chosen by the {@code client_id} claim of an already validated token, so one client cannot
 * spend another's allowance, and an unauthenticated caller never creates a bucket.
 *
 * <p>
 * The producer endpoint {@code POST /events} has its own allowance, {@code producer-requests-per-minute}, kept in a
 * separate bucket per producer, so its ingestion calls never use up the self-service allowance of the same client.
 * Like the self-service limit, it is counted per instance (D29).
 *
 * <p>
 * Runs after the security filter chain. Excess requests get {@code 429} as {@code application/problem+json} with a
 * {@code Retry-After} in whole seconds.
 */
@Component
@ConditionalOnProperty(name = "app.rate-limit.enabled", havingValue = "true", matchIfMissing = true)
class ClientRateLimitFilter extends OncePerRequestFilter {

	private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

	private final Map<String, Bucket> producerBuckets = new ConcurrentHashMap<>();

	private final int requestsPerMinute;

	private final int producerRequestsPerMinute;

	ClientRateLimitFilter(@Value("${app.rate-limit.requests-per-minute:100}") int requestsPerMinute,
			@Value("${app.rate-limit.producer-requests-per-minute:600}") int producerRequestsPerMinute) {
		if (requestsPerMinute < 1) {
			throw new IllegalArgumentException("app.rate-limit.requests-per-minute must be at least 1");
		}
		if (producerRequestsPerMinute < 1) {
			throw new IllegalArgumentException("app.rate-limit.producer-requests-per-minute must be at least 1");
		}
		this.requestsPerMinute = requestsPerMinute;
		this.producerRequestsPerMinute = producerRequestsPerMinute;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		String path = request.getRequestURI();
		return !(path.equals("/notification_events") || path.startsWith("/notification_events/")
				|| path.equals("/subscriptions") || path.startsWith("/subscriptions/") || path.equals("/events"));
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (!(authentication != null && authentication.getPrincipal() instanceof Jwt jwt)
				|| jwt.getClaimAsString(SecurityConfig.CLIENT_ID_CLAIM) == null) {
			chain.doFilter(request, response);
			return;
		}
		boolean producer = request.getRequestURI().equals("/events");
		Map<String, Bucket> allowances = producer ? producerBuckets : buckets;
		int limit = producer ? producerRequestsPerMinute : requestsPerMinute;
		Bucket bucket = allowances.computeIfAbsent(jwt.getClaimAsString(SecurityConfig.CLIENT_ID_CLAIM),
				client -> newBucket(limit));
		ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
		if (probe.isConsumed()) {
			chain.doFilter(request, response);
			return;
		}
		long retryAfterSeconds = Math.max(1, (probe.getNanosToWaitForRefill() + 999_999_999L) / 1_000_000_000L);
		response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
		ProblemResponses.write(response, 429, "Too Many Requests",
				"Rate limit exceeded. Retry after the time given in the Retry-After header.");
	}

	private static Bucket newBucket(int requestsPerMinute) {
		return Bucket.builder()
			.addLimit(Bandwidth.builder()
				.capacity(requestsPerMinute)
				.refillIntervally(requestsPerMinute, Duration.ofMinutes(1))
				.build())
			.build();
	}

}
