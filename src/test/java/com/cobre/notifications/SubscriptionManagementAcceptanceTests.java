package com.cobre.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.cobre.notifications.shared.crypto.SecretCipher;
import com.cobre.notifications.shared.crypto.WebhookSigner;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * One test per scenario in {@code openspec/changes/add-subscription-management/specs/notification-delivery/spec.md}
 * and {@code specs/platform-security/spec.md}, over HTTP. {@link FakeDnsConfiguration} makes allowlisted test hosts
 * resolve to public or internal addresses.
 */
// Same configuration as the other acceptance tests, so the cached context and its containers are reused.
@SpringBootTest(properties = "app.seed.enabled=true")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SubscriptionManagementAcceptanceTests {

	private static final TestJwtIssuer ISSUER = TestJwtIssuer.trusted();

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final String VALID_BODY = """
			{"url":"https://hooks.example.org/cobre","event_types":["credit_deposit"]}""";

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private SecretCipher secretCipher;

	@Autowired
	private WebhookSigner webhookSigner;

	/**
	 * The context is shared with tests that count the seeded rows, so every test removes the subscriptions it made.
	 */
	@AfterEach
	void deleteTestSubscriptions() {
		jdbc.sql("DELETE FROM subscriptions WHERE starts_with(client_id, 'SUBTEST_')").update();
	}

	@Nested
	class CreateASubscription {

		@Test
		void validSubscription() throws Exception {
			String clientId = "SUBTEST_CREATE";

			JsonNode created = created(mvc.perform(asWriter(clientId, post("/subscriptions"), VALID_BODY))
				.andExpect(status().isCreated())
				.andExpect(header().string(HttpHeaders.LOCATION, startsWith("/subscriptions/")))
				.andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
				.andExpect(jsonPath("$.subscription_id").isNotEmpty())
				.andExpect(jsonPath("$.url").value("https://hooks.example.org/cobre"))
				.andExpect(jsonPath("$.event_types").value(containsInAnyOrder("credit_deposit")))
				.andExpect(jsonPath("$.active").value(true))
				.andExpect(jsonPath("$.secret").value(startsWith("whsec_"))));

			String id = created.get("subscription_id").asString();
			assertThat(ownerOf(id)).isEqualTo(clientId);
			mvc.perform(asReader(clientId, get("/subscriptions/" + id))).andExpect(status().isOk());
		}

		@ParameterizedTest
		@ValueSource(strings = { "http://hooks.example.org/cobre", "https://not-allowed.example.net/",
				"https://loopback.example.org/", "https://private.example.org/", "https://metadata.example.org/latest",
				"https://hooks.example.org:8443/cobre" })
		void unsafeDestination(String url) throws Exception {
			String clientId = "SUBTEST_UNSAFE";

			mvc.perform(asWriter(clientId, post("/subscriptions"),
					"{\"url\":\"" + url + "\",\"event_types\":[\"credit_deposit\"]}"))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.detail").value(startsWith("url is not an allowed destination")));

			assertThat(countFor(clientId)).isZero();
		}

		@Test
		void ownerCannotBeChosen() throws Exception {
			mvc.perform(asWriter("SUBTEST_OWNER", post("/subscriptions"), """
					{"url":"https://hooks.example.org/cobre","event_types":["credit_deposit"],"client_id":"SUBTEST_VICTIM"}"""))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.detail").value("Field 'client_id' is not accepted by this operation."));

			assertThat(countFor("SUBTEST_OWNER")).isZero();
			assertThat(countFor("SUBTEST_VICTIM")).isZero();
		}

		@ParameterizedTest
		@ValueSource(strings = { "{\"url\":\"https://hooks.example.org/cobre\",\"event_types\":[]}",
				"{\"url\":\"https://hooks.example.org/cobre\",\"event_types\":[\"Credit-Deposit\"]}",
				"{\"url\":\"https://hooks.example.org/cobre\"}", "{\"event_types\":[\"credit_deposit\"]}", "not json",
				"" })
		void invalidBodies(String body) throws Exception {
			mvc.perform(asWriter("SUBTEST_INVALID", post("/subscriptions"), body))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.detail").isNotEmpty());

			assertThat(countFor("SUBTEST_INVALID")).isZero();
		}

		@Test
		void writeScopeIsRequired() throws Exception {
			mvc.perform(with(ISSUER.token("SUBTEST_SCOPE", "subscriptions:read"), post("/subscriptions"), VALID_BODY))
				.andExpect(status().isForbidden());

			assertThat(countFor("SUBTEST_SCOPE")).isZero();
		}

	}

	@Nested
	class ReadSubscriptions {

		@Test
		void listOwnSubscriptions() throws Exception {
			String clientId = "SUBTEST_LIST";
			String first = createdId(clientId);
			String second = createdId(clientId);
			createdId("SUBTEST_LIST_OTHER");

			String body = mvc.perform(asReader(clientId, get("/subscriptions")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[*].subscription_id").value(containsInAnyOrder(first, second)))
				.andReturn()
				.getResponse()
				.getContentAsString();

			assertThat(body).doesNotContain("secret").doesNotContain("whsec_");
		}

		@Test
		void getByIdNeverReturnsTheSecret() throws Exception {
			String clientId = "SUBTEST_GET";
			JsonNode created = created(mvc.perform(asWriter(clientId, post("/subscriptions"), VALID_BODY)));
			String secret = created.get("secret").asString();

			String body = mvc
				.perform(asReader(clientId, get("/subscriptions/" + created.get("subscription_id").asString())))
				.andExpect(status().isOk())
				.andReturn()
				.getResponse()
				.getContentAsString();

			assertThat(body).doesNotContain("secret").doesNotContain(secret);
		}

		@Test
		void subscriptionOfAnotherClient() throws Exception {
			String foreignId = createdId("SUBTEST_FOREIGN");

			String foreign = mvc.perform(asReader("SUBTEST_READER", get("/subscriptions/" + foreignId)))
				.andExpect(status().isNotFound())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andReturn()
				.getResponse()
				.getContentAsString()
				.replace(foreignId, "{id}");
			String missing = mvc.perform(asReader("SUBTEST_READER", get("/subscriptions/does-not-exist")))
				.andExpect(status().isNotFound())
				.andReturn()
				.getResponse()
				.getContentAsString()
				.replace("does-not-exist", "{id}");

			assertThat(foreign).isEqualTo(missing);
		}

		@Test
		void readScopeIsRequired() throws Exception {
			mvc.perform(with(ISSUER.token("SUBTEST_LIST", "notifications:read"), get("/subscriptions"), null))
				.andExpect(status().isForbidden());
		}

	}

	@Nested
	class DeactivateASubscription {

		@Test
		void deactivatedSubscription() throws Exception {
			String clientId = "SUBTEST_DELETE";
			String id = createdId(clientId);

			mvc.perform(asWriter(clientId, delete("/subscriptions/" + id), null)).andExpect(status().isNoContent());

			mvc.perform(asReader(clientId, get("/subscriptions")))
				.andExpect(jsonPath("$.data[?(@.subscription_id == '" + id + "')].active").value(hasItem(false)));
			assertThat(ownerOf(id)).as("logical deletion keeps the row").isEqualTo(clientId);
		}

		@Test
		void deactivatingTwiceIsANoOp() throws Exception {
			String id = createdId("SUBTEST_DELETE_TWICE");

			mvc.perform(asWriter("SUBTEST_DELETE_TWICE", delete("/subscriptions/" + id), null))
				.andExpect(status().isNoContent());
			mvc.perform(asWriter("SUBTEST_DELETE_TWICE", delete("/subscriptions/" + id), null))
				.andExpect(status().isNoContent());
		}

		@Test
		void anotherClientCannotDeactivate() throws Exception {
			String id = createdId("SUBTEST_DELETE_OWNER");

			mvc.perform(asWriter("SUBTEST_DELETE_INTRUDER", delete("/subscriptions/" + id), null))
				.andExpect(status().isNotFound());

			mvc.perform(asReader("SUBTEST_DELETE_OWNER", get("/subscriptions/" + id)))
				.andExpect(jsonPath("$.active").value(true));
		}

	}

	@Nested
	class UpdateASubscription {

		@Test
		void pauseASubscription() throws Exception {
			String id = createdId("SUBTEST_PATCH");

			mvc.perform(asWriter("SUBTEST_PATCH", patch("/subscriptions/" + id), "{\"active\":false}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.active").value(false))
				.andExpect(jsonPath("$.event_types").value(containsInAnyOrder("credit_deposit")));
		}

		@Test
		void changeEventTypes() throws Exception {
			String id = createdId("SUBTEST_PATCH");

			mvc.perform(asWriter("SUBTEST_PATCH", patch("/subscriptions/" + id),
					"{\"event_types\":[\"debit_withdrawal\",\"credit_transfer\"]}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.active").value(true))
				.andExpect(jsonPath("$.event_types").value(containsInAnyOrder("debit_withdrawal", "credit_transfer")));
		}

		@Test
		void attemptToChangeTheUrl() throws Exception {
			String id = createdId("SUBTEST_PATCH");

			mvc.perform(asWriter("SUBTEST_PATCH", patch("/subscriptions/" + id),
					"{\"url\":\"https://presentation.example.com/elsewhere\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.detail").value("Field 'url' is not accepted by this operation."));

			mvc.perform(asReader("SUBTEST_PATCH", get("/subscriptions/" + id)))
				.andExpect(jsonPath("$.url").value("https://hooks.example.org/cobre"))
				.andExpect(jsonPath("$.active").value(true));
		}

		@ParameterizedTest
		@ValueSource(strings = { "{}", "{\"active\":null}", "{\"event_types\":[]}", "{\"active\":false,\"secret\":\"x\"}" })
		void invalidPatches(String body) throws Exception {
			String id = createdId("SUBTEST_PATCH");

			mvc.perform(asWriter("SUBTEST_PATCH", patch("/subscriptions/" + id), body))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

			mvc.perform(asReader("SUBTEST_PATCH", get("/subscriptions/" + id))).andExpect(jsonPath("$.active").value(true));
		}

		@Test
		void anotherClientsSubscriptionIsNotFound() throws Exception {
			String id = createdId("SUBTEST_PATCH_OWNER");

			mvc.perform(asWriter("SUBTEST_PATCH_INTRUDER", patch("/subscriptions/" + id), "{\"active\":false}"))
				.andExpect(status().isNotFound());
		}

	}

	@Nested
	class DestinationPolicy {

		@Test
		void presentationHost() throws Exception {
			// presentation.example.com is allowed only through app.webhook.allowed-hosts (WEBHOOK_ALLOWED_HOSTS).
			mvc.perform(asWriter("SUBTEST_DEMO", post("/subscriptions"),
					"{\"url\":\"https://presentation.example.com/webhooks\",\"event_types\":[\"credit_deposit\"]}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.url").value(endsWith("/webhooks")));
		}

	}

	@Nested
	class WebhookSecrets {

		@Test
		void secretStoredEncrypted() throws Exception {
			JsonNode created = created(mvc.perform(asWriter("SUBTEST_SECRET", post("/subscriptions"), VALID_BODY)));
			byte[] secret = created.get("secret").asString().getBytes(StandardCharsets.UTF_8);

			byte[] stored = jdbc.sql("SELECT secret_ciphertext FROM subscriptions WHERE id = :id")
				.param("id", created.get("subscription_id").asString())
				.query(byte[].class)
				.single();

			assertThat(stored).isNotEqualTo(secret);
			assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain(created.get("secret").asString());
			// The ciphertext is bound to its subscription id (associated data).
			assertThat(secretCipher.decrypt(stored, created.get("subscription_id").asString())).isEqualTo(secret);
		}

	}

	@Nested
	class WebhookSignature {

		@Test
		void knownTestVector() {
			assertThat(webhookSigner.sign("test-secret", 1700000000L, "{\"a\":1}".getBytes(StandardCharsets.UTF_8)))
				.isEqualTo("t=1700000000,v1=8cb2c3355fca388e9ac2caec004f4d5d7045d74937ab5faad61dc11682247a9f");
		}

	}

	private String createdId(String clientId) throws Exception {
		return created(mvc.perform(asWriter(clientId, post("/subscriptions"), VALID_BODY)).andExpect(status().isCreated()))
			.get("subscription_id")
			.asString();
	}

	private static JsonNode created(ResultActions result) throws Exception {
		return JSON.readTree(result.andReturn().getResponse().getContentAsString());
	}

	private long countFor(String clientId) {
		return jdbc.sql("SELECT count(*) FROM subscriptions WHERE client_id = :clientId")
			.param("clientId", clientId)
			.query(Long.class)
			.single();
	}

	private String ownerOf(String id) {
		return jdbc.sql("SELECT client_id FROM subscriptions WHERE id = :id").param("id", id).query(String.class).single();
	}

	private static MockHttpServletRequestBuilder asReader(String clientId, MockHttpServletRequestBuilder request) {
		return with(ISSUER.token(clientId, "subscriptions:read"), request, null);
	}

	private static MockHttpServletRequestBuilder asWriter(String clientId, MockHttpServletRequestBuilder request,
			String body) {
		return with(ISSUER.token(clientId, "subscriptions:write"), request, body);
	}

	private static MockHttpServletRequestBuilder with(String token, MockHttpServletRequestBuilder request,
			String body) {
		request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
		return body == null ? request : request.contentType(MediaType.APPLICATION_JSON).content(body);
	}

}
