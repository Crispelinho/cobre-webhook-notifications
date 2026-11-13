package com.cobre.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * One test per scenario in {@code openspec/changes/add-observability-and-audit/specs/platform-security/spec.md} (rate
 * limiting). The test configuration turns the limit off for every other test; this class turns it on.
 */
@SpringBootTest(properties = "app.rate-limit.enabled=true")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class RateLimitAcceptanceTests {

	private static final TestJwtIssuer ISSUER = TestJwtIssuer.trusted();

	@Autowired
	private MockMvc mvc;

	@Test
	void aClientExceedingItsLimitGetsTooManyRequestsWithoutAffectingAnotherClient() throws Exception {
		for (int request = 1; request <= 100; request++) {
			mvc.perform(list("RATELIMIT_A")).andExpect(status().isOk());
		}

		mvc.perform(list("RATELIMIT_A"))
			.andExpect(status().isTooManyRequests())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.status").value(429))
			.andExpect(header().exists(HttpHeaders.RETRY_AFTER));
		String retryAfter = mvc.perform(list("RATELIMIT_A"))
			.andExpect(status().isTooManyRequests())
			.andReturn()
			.getResponse()
			.getHeader(HttpHeaders.RETRY_AFTER);
		assertThat(Long.parseLong(retryAfter)).isBetween(1L, 60L);

		mvc.perform(list("RATELIMIT_B")).andExpect(status().isOk());
	}

	@Test
	void theLimitAppliesToEveryEndpointOfTheSelfServiceApi() throws Exception {
		for (int request = 1; request <= 100; request++) {
			mvc.perform(list("RATELIMIT_C")).andExpect(status().isOk());
		}

		mvc.perform(get("/notification_events").header(HttpHeaders.AUTHORIZATION,
				"Bearer " + ISSUER.token("RATELIMIT_C", "notifications:read"))).andExpect(status().isTooManyRequests());
	}

	@Test
	void unauthenticatedRequestsAreRejectedByAuthenticationNotCountedAgainstAnyone() throws Exception {
		for (int request = 1; request <= 105; request++) {
			mvc.perform(get("/subscriptions")).andExpect(status().isUnauthorized());
		}
	}

	private static MockHttpServletRequestBuilder list(String clientId) {
		return get("/subscriptions").header(HttpHeaders.AUTHORIZATION,
				"Bearer " + ISSUER.token(clientId, "subscriptions:read"));
	}

}
