package com.cobre.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.context.annotation.Import;

import com.cobre.notifications.support.WebhookTestServer;

/**
 * With {@code app.webhook.allowed-hosts=*} in the local profile, any public host name is accepted at registration,
 * and private addresses are still rejected.
 */
@SpringBootTest(properties = "app.webhook.allowed-hosts=*")
@ActiveProfiles("local")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AnyPublicHostSubscriptionAcceptanceTests {

	private static final TestJwtIssuer ISSUER = TestJwtIssuer.trusted();

	@Autowired
	private MockMvc mvc;

	@Test
	void anyPublicHostIsAcceptedForRegistration() throws Exception {
		mvc.perform(writer("ANYHOST_CLIENT", "{\"url\":\"https://anything.example.net/cobre\",\"event_types\":[\"credit_deposit\"]}"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.url").value("https://anything.example.net/cobre"));
	}

	@Test
	void aPrivateAddressIsStillRejectedWithTheWildcard() throws Exception {
		mvc.perform(writer("ANYHOST_CLIENT", "{\"url\":\"https://private.example.org/cobre\",\"event_types\":[\"credit_deposit\"]}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.startsWith("url is not an allowed destination")));
	}

	private MockHttpServletRequestBuilder writer(String clientId, String body) {
		return post("/subscriptions")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ISSUER.token(clientId, "subscriptions:write"))
			.contentType(MediaType.APPLICATION_JSON)
			.content(body);
	}
}
