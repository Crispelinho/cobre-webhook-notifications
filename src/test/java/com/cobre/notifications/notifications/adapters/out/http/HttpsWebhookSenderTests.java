package com.cobre.notifications.notifications.adapters.out.http;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.cobre.notifications.notifications.domain.AttemptResult;
import com.cobre.notifications.notifications.domain.Delivery;
import com.cobre.notifications.notifications.domain.DeliveryOutcome;
import com.cobre.notifications.notifications.domain.DeliveryStatus;
import com.cobre.notifications.notifications.domain.NotificationEventId;
import com.cobre.notifications.notifications.domain.TransportError;
import com.cobre.notifications.support.WebhookTestServer;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

import tools.jackson.databind.json.JsonMapper;

/**
 * The HTTPS adapter against a real TLS receiver: what it sends, what it reports, and that production TLS stays strict.
 */
class HttpsWebhookSenderTests {

	@RegisterExtension
	static WireMockExtension receiver = WebhookTestServer.extension();

	private final JsonMapper json = JsonMapper.builder().build();

	private final HttpClient trusting = WebhookTestServer.trustingHttpClient(Duration.ofSeconds(2));

	private HttpsWebhookSender sender(HttpClient client, Duration requestTimeout) {
		return new HttpsWebhookSender(client, json, requestTimeout);
	}

	@Test
	void postsTheEventAsJsonWithTheNotificationIdHeader() {
		receiver.stubFor(post("/hook").willReturn(aResponse().withStatus(200)));

		AttemptResult result = sender(trusting, Duration.ofSeconds(5)).send(delivery(url("/hook")));

		assertThat(result.httpStatus()).isEqualTo(200);
		assertThat(result.outcome()).isEqualTo(DeliveryOutcome.SUCCESS);
		assertThat(result.errorType()).isNull();
		receiver.verify(1, postRequestedFor(urlEqualTo("/hook"))
			.withHeader("Cobre-Notification-Id", equalTo("N1"))
			.withHeader("Content-Type", equalTo("application/json"))
			.withRequestBody(equalToJson("""
					{"notification_event_id":"N1","event_id":"E1","event_type":"credit_deposit",
					 "content":"Deposit of $10 \\"received\\"","created_at":"2026-01-01T00:00:00Z"}
					""")));
	}

	@Test
	void aRedirectIsReturnedAsAPermanentFailureAndNeverFollowed() {
		receiver.stubFor(post("/moved").willReturn(aResponse().withStatus(302).withHeader("Location", url("/elsewhere"))));
		receiver.stubFor(post("/elsewhere").willReturn(aResponse().withStatus(200)));
		receiver.stubFor(get("/elsewhere").willReturn(aResponse().withStatus(200)));

		AttemptResult result = sender(trusting, Duration.ofSeconds(5)).send(delivery(url("/moved")));

		assertThat(result.httpStatus()).isEqualTo(302);
		assertThat(result.outcome()).isEqualTo(DeliveryOutcome.PERMANENT);
		assertThat(result.errorType()).isEqualTo("http_302");
		assertThat(receiver.findAll(com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor(urlEqualTo("/elsewhere"))))
			.isEmpty();
	}

	@Test
	void aSlowReceiverIsATimeout() {
		receiver.stubFor(post("/slow").willReturn(aResponse().withStatus(200).withFixedDelay(3_000)));

		AttemptResult result = sender(trusting, Duration.ofMillis(500)).send(delivery(url("/slow")));

		assertThat(result.error()).isEqualTo(TransportError.TIMEOUT);
		assertThat(result.errorType()).isEqualTo("timeout");
		assertThat(result.outcome()).isEqualTo(DeliveryOutcome.RETRYABLE);
	}

	@Test
	void anUnreachablePortIsAConnectionError() throws Exception {
		int closedPort;
		try (ServerSocket socket = new ServerSocket(0)) {
			closedPort = socket.getLocalPort();
		}

		AttemptResult result = sender(trusting, Duration.ofSeconds(5))
			.send(delivery("https://localhost:" + closedPort + "/hook"));

		assertThat(result.error()).isEqualTo(TransportError.CONNECTION);
		assertThat(result.outcome()).isEqualTo(DeliveryOutcome.RETRYABLE);
	}

	@Test
	void productionTlsRejectsAnUntrustedCertificate() {
		receiver.stubFor(post("/hook").willReturn(aResponse().withStatus(200)));
		HttpClient production = new WebhookHttpClientConfiguration().webhookHttpClient(Duration.ofSeconds(2));

		AttemptResult result = sender(production, Duration.ofSeconds(5)).send(delivery(url("/hook")));

		assertThat(result.error()).isEqualTo(TransportError.TLS);
		assertThat(result.outcome()).isEqualTo(DeliveryOutcome.PERMANENT);
		receiver.verify(0, postRequestedFor(urlEqualTo("/hook")));
	}

	@Test
	void plainHttpIsNeverSent() {
		AttemptResult result = sender(trusting, Duration.ofSeconds(5))
			.send(delivery("http://localhost:" + receiver.getPort() + "/hook"));

		assertThat(result.error()).isEqualTo(TransportError.INVALID_DESTINATION);
		assertThat(result.outcome()).isEqualTo(DeliveryOutcome.PERMANENT);
		assertThat(receiver.getAllServeEvents()).isEmpty();
	}

	@Test
	void anUnparseableDestinationIsInvalid() {
		AttemptResult result = sender(trusting, Duration.ofSeconds(5)).send(delivery("https://exa mple.com/ho ok"));

		assertThat(result.error()).isEqualTo(TransportError.INVALID_DESTINATION);
	}

	private static String url(String path) {
		return "https://localhost:" + receiver.getHttpsPort() + path;
	}

	private static Delivery delivery(String destination) {
		return new Delivery(new NotificationEventId("N1"), "E1", "credit_deposit", "Deposit of $10 \"received\"",
				destination, Instant.parse("2026-01-01T00:00:00Z"), DeliveryStatus.PENDING, 1, 0, null, null, null, 0);
	}

}
