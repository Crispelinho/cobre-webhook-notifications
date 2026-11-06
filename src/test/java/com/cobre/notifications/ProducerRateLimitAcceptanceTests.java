package com.cobre.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
 * The producer allowance on {@code POST /events}: a separate limit per producer, so one producer that exhausts its
 * allowance gets {@code 429} while another producer is not affected. Runs with a limit of 3 per minute to keep the
 * test short.
 */
@SpringBootTest(properties = { "app.rate-limit.enabled=true", "app.rate-limit.producer-requests-per-minute=3" })
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ProducerRateLimitAcceptanceTests {

	private static final TestJwtIssuer ISSUER = TestJwtIssuer.trusted();

	@Autowired
	private MockMvc mvc;

	@Test
	void aProducerExceedingItsAllowanceGetsTooManyRequestsWithoutAffectingAnotherProducer() throws Exception {
		for (int request = 1; request <= 3; request++) {
			assertThat(ingest("producer-rl-a").andReturn().getResponse().getStatus()).isNotEqualTo(429);
		}

		ingest("producer-rl-a").andExpect(status().isTooManyRequests())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.status").value(429))
			.andExpect(header().exists(HttpHeaders.RETRY_AFTER));

		assertThat(ingest("producer-rl-b").andReturn().getResponse().getStatus()).isNotEqualTo(429);
	}

	@Test
	void unauthenticatedRequestsToIngestionAreRejectedWithoutBeingCounted() throws Exception {
		for (int request = 1; request <= 10; request++) {
			mvc.perform(post("/events").contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isUnauthorized());
		}
	}

	private org.springframework.test.web.servlet.ResultActions ingest(String producerId) throws Exception {
		MockHttpServletRequestBuilder request = post("/events").header(HttpHeaders.AUTHORIZATION,
				"Bearer " + ISSUER.token(producerId, "events:write"))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{}");
		return mvc.perform(request);
	}

}
