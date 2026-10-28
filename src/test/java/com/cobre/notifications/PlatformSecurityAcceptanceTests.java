package com.cobre.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * One test per scenario in {@code openspec/changes/add-self-service-query/specs/platform-security/spec.md} (OWASP API1
 * and API2), over HTTP.
 */
@SpringBootTest(properties = "app.seed.enabled=true")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PlatformSecurityAcceptanceTests {

	private static final TestJwtIssuer ISSUER = TestJwtIssuer.trusted();

	@Autowired
	private MockMvc mvc;

	@Test
	void missingToken() throws Exception {
		expectUnauthorized(mvc.perform(get("/notification_events")));
	}

	@Test
	void expiredToken() throws Exception {
		String token = ISSUER.token(claims -> claims.claim("client_id", "CLIENT001")
			.claim("scope", "notifications:read")
			.issuedAt(Instant.now().minusSeconds(7200))
			.expiresAt(Instant.now().minusSeconds(300)));

		expectUnauthorized(call(token));
	}

	@Test
	void tokenSignedWithAnUnknownKey() throws Exception {
		expectUnauthorized(call(TestJwtIssuer.untrusted().token("CLIENT001", "notifications:read")));
	}

	@Test
	void tokenWithoutClientIdentity() throws Exception {
		expectUnauthorized(call(ISSUER.token(claims -> claims.claim("scope", "notifications:read"))));
	}

	@Test
	void missingReadScope() throws Exception {
		call(ISSUER.token("CLIENT001", "notifications:replay")).andExpect(status().isForbidden())
			.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"insufficient_scope\""))
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
	}

	@Test
	void endpointsWithoutAnExplicitRuleAreForbidden() throws Exception {
		mvc.perform(post("/notification_events").header(HttpHeaders.AUTHORIZATION,
				"Bearer " + ISSUER.token("CLIENT001", "notifications:read")))
			.andExpect(status().isForbidden());
	}

	@Test
	void crossClientAccessIsIndistinguishableFromAMissingResource() throws Exception {
		String token = ISSUER.token("CLIENT001", "notifications:read");
		String foreign = mvc.perform(get("/notification_events/EVT003").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
			.andExpect(status().isNotFound())
			.andReturn()
			.getResponse()
			.getContentAsString()
			.replace("/notification_events/EVT003", "/notification_events/{id}");
		String missing = mvc
			.perform(get("/notification_events/DOES-NOT-EXIST").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
			.andExpect(status().isNotFound())
			.andReturn()
			.getResponse()
			.getContentAsString()
			.replace("/notification_events/DOES-NOT-EXIST", "/notification_events/{id}");

		assertThat(foreign).isEqualTo(missing);
	}

	private ResultActions call(String token) throws Exception {
		return mvc.perform(get("/notification_events").header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
	}

	/**
	 * The header and body must not reveal why the token was rejected (expiry, missing claim, wrong key).
	 */
	private static void expectUnauthorized(ResultActions result) throws Exception {
		result.andExpect(status().isUnauthorized())
			.andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(content().string(not(containsString("expired"))))
			.andExpect(content().string(not(containsString("client_id"))));
	}

}
