package com.cobre.notifications.notifications.adapters.out.http;

import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.UnknownHostException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.message.BasicClassicHttpResponse;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;

import com.cobre.notifications.support.DrippingTlsReceiver;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.cobre.notifications.notifications.domain.AttemptResult;
import com.cobre.notifications.notifications.domain.Delivery;
import com.cobre.notifications.notifications.domain.DeliveryOutcome;
import com.cobre.notifications.notifications.domain.DeliveryStatus;
import com.cobre.notifications.notifications.domain.NotificationEventId;
import com.cobre.notifications.notifications.domain.TransportError;
import com.cobre.notifications.shared.crypto.SecretCipher;
import com.cobre.notifications.shared.crypto.WebhookSigner;
import com.cobre.notifications.shared.security.DestinationPolicy;
import com.cobre.notifications.support.WebhookTestServer;
import com.github.tomakehurst.wiremock.http.Request;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.matching.MatchResult;

import tools.jackson.databind.json.JsonMapper;

/**
 * The HTTPS adapter against a real TLS receiver: what it sends, what it reports, that production TLS stays strict, that
 * the shared destination policy runs before every request and that requests are signed with the subscription secret.
 * <p>
 * The destination policy only accepts {@code https} on port 443 for allowlisted public hosts, so these tests use a
 * policy-valid URL ({@code https://hooks.example.org/...}) and a client that redirects the request to the local TLS
 * receiver ({@link WebhookTestServer#redirectingTo}). The policy and the sender are the production classes.
 */
class HttpsWebhookSenderTests {

	@RegisterExtension
	static WireMockExtension receiver = WebhookTestServer.extension();

	private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

	/**
	 * The exact body sent for {@link #delivery(String, String)}, and the HMAC-SHA256 of {@code "1767225600." + body}
	 * with the secret {@code test-secret}, computed outside this code base with
	 * {@code printf '%s' "<t>.<body>" | openssl dgst -sha256 -hmac test-secret}.
	 */
	private static final String VECTOR_BODY = "{\"notification_event_id\":\"N1\",\"event_id\":\"E1\",\"event_type\":\"credit_deposit\",\"content\":\"Deposit of $10 \\\"received\\\"\",\"created_at\":\"2026-01-01T00:00:00Z\"}";

	private static final String VECTOR_HMAC = "a16567b3c37b42b7439b0bb82c7db715e57450cef0f4233042a0f9222898759c";

	// Names resolve from a fixed map, never from real DNS. "unlisted" is public but not in the allowlist.
	private static final Map<String, String> DNS = Map.of("hooks.example.org", "93.184.215.14",
			"loopback.example.org", "127.0.0.1", "private.example.org", "10.0.0.5", "metadata.example.org",
			"169.254.169.254", "unlisted.example.org", "93.184.215.99", "mismatch.example.org", "93.184.215.30");

	private final JsonMapper json = JsonMapper.builder().build();

	private final SecretCipher cipher = SecretCipher
		.fromBase64Key(Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes()));

	private final DestinationPolicy policy = new DestinationPolicy(
			List.of("hooks.example.org", "loopback.example.org", "private.example.org", "metadata.example.org",
					"mismatch.example.org", "dns-down.example.org"),
			host -> {
				String address = DNS.get(host);
				if (address == null) {
					throw new UnknownHostException(host);
				}
				return List.of(InetAddress.getByName(address));
			});

	private final List<InetSocketAddress> connections = new ArrayList<>();

	private final CloseableHttpClient trusting = WebhookTestServer.trustingHttpClient(receiver::getHttpsPort);

	private HttpsWebhookSender sender(CloseableHttpClient client, Duration requestTimeout) {
		return new HttpsWebhookSender(client, json, policy, cipher, new WebhookSigner(), requestTimeout,
				Clock.fixed(NOW, ZoneOffset.UTC));
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
		CloseableHttpClient closed = WebhookTestServer.redirectingHttpClient(WebhookTestServer.trustingSslContext(),
				() -> closedPort, Duration.ofSeconds(2), requested -> {
				}, address -> false);

		AttemptResult result = sender(closed, Duration.ofSeconds(5)).send(delivery(url("/hook")));

		assertThat(result.error()).isEqualTo(TransportError.CONNECTION);
		assertThat(result.outcome()).isEqualTo(DeliveryOutcome.RETRYABLE);
	}

	@Test
	void productionTlsRejectsAnUntrustedCertificate() {
		receiver.stubFor(post("/hook").willReturn(aResponse().withStatus(200)));
		// The JVM default trust, like production: the test certificate is self-signed and must be rejected.
		CloseableHttpClient production = WebhookTestServer.redirectingHttpClient(WebhookTestServer.defaultSslContext(),
				receiver::getHttpsPort, Duration.ofSeconds(2), requested -> {
				}, address -> false);

		AttemptResult result = sender(production, Duration.ofSeconds(5)).send(delivery(url("/hook")));

		assertThat(result.error()).isEqualTo(TransportError.TLS);
		assertThat(result.outcome()).isEqualTo(DeliveryOutcome.PERMANENT);
		receiver.verify(0, postRequestedFor(urlEqualTo("/hook")));
	}

	@Test
	void plainHttpIsNeverSent() {
		AttemptResult result = sender(trusting, Duration.ofSeconds(5))
			.send(delivery("http://hooks.example.org/hook"));

		assertThat(result.error()).isEqualTo(TransportError.INVALID_DESTINATION);
		assertThat(result.outcome()).isEqualTo(DeliveryOutcome.PERMANENT);
		assertThat(receiver.getAllServeEvents()).isEmpty();
	}

	@Test
	void anUnparseableDestinationIsInvalid() {
		AttemptResult result = sender(trusting, Duration.ofSeconds(5)).send(delivery("https://exa mple.com/ho ok"));

		assertThat(result.error()).isEqualTo(TransportError.INVALID_DESTINATION);
		assertThat(receiver.getAllServeEvents()).isEmpty();
	}

	@Test
	void aHostResolvingToAPrivateOrLoopbackAddressIsRejectedAndNothingIsSent() {
		receiver.stubFor(post("/hook").willReturn(aResponse().withStatus(200)));

		for (String host : List.of("private.example.org", "loopback.example.org", "metadata.example.org")) {
			AttemptResult result = sender(trusting, Duration.ofSeconds(5)).send(delivery("https://" + host + "/hook"));

			assertThat(result.error()).as(host).isEqualTo(TransportError.INVALID_DESTINATION);
			assertThat(result.errorType()).isEqualTo("invalid_destination");
			assertThat(result.outcome()).isEqualTo(DeliveryOutcome.PERMANENT);
		}
		assertThat(receiver.getAllServeEvents()).isEmpty();
	}

	@Test
	void aHostThatDoesNotResolveIsARetryableDnsAttemptAndNothingIsSent() {
		// "dns-down.example.org" is allowlisted but missing from the fixed DNS map, so the lookup fails like NXDOMAIN.
		AttemptResult result = sender(trusting, Duration.ofSeconds(5)).send(delivery("https://dns-down.example.org/hook"));

		assertThat(result.error()).isEqualTo(TransportError.DNS);
		assertThat(result.errorType()).isEqualTo("dns_error");
		assertThat(result.outcome()).isEqualTo(DeliveryOutcome.RETRYABLE);
		assertThat(receiver.getAllServeEvents()).isEmpty();
	}

	@Test
	void hostsOutsideTheAllowlistNonStandardPortsAndUserInfoAreRejected() {
		for (String url : List.of("https://unlisted.example.org/hook", "https://hooks.example.org:8443/hook",
				"https://user:pw@hooks.example.org/hook", "https://nxdomain.example.org/hook")) {
			AttemptResult result = sender(trusting, Duration.ofSeconds(5)).send(delivery(url));

			assertThat(result.error()).as(url).isEqualTo(TransportError.INVALID_DESTINATION);
		}
		assertThat(receiver.getAllServeEvents()).isEmpty();
	}

	@Test
	void theConnectionGoesToTheValidatedAddressEvenIfASecondLookupWouldAnswerDifferently() {
		receiver.stubFor(post("/pinned").willReturn(aResponse().withStatus(200)));
		// DNS rebinding: the first answer (the one the policy validates) is one public address, every later one another.
		AtomicInteger lookups = new AtomicInteger();
		DestinationPolicy rebinding = new DestinationPolicy(List.of("hooks.example.org"), host -> {
			String address = lookups.getAndIncrement() == 0 ? "93.184.215.14" : "93.184.215.99";
			return List.of(InetAddress.getByName(address));
		});
		CloseableHttpClient recording = WebhookTestServer.redirectingHttpClient(WebhookTestServer.trustingSslContext(),
				receiver::getHttpsPort, Duration.ofSeconds(2), connections::add, address -> false);
		HttpsWebhookSender pinned = new HttpsWebhookSender(recording, json, rebinding, cipher, new WebhookSigner(),
				Duration.ofSeconds(5), Clock.fixed(NOW, ZoneOffset.UTC));

		AttemptResult result = pinned.send(delivery(url("/pinned")));

		assertThat(result.outcome()).isEqualTo(DeliveryOutcome.SUCCESS);
		assertThat(lookups).hasValue(1);
		// The request was opened to the validated address and port 443, and to nothing else; the test client cannot
		// resolve names, so a second lookup could not have been used even by accident.
		assertThat(connections).singleElement().satisfies(requested -> {
			assertThat(requested.getAddress().getHostAddress()).isEqualTo("93.184.215.14");
			assertThat(requested.getPort()).isEqualTo(443);
		});
		receiver.verify(1, postRequestedFor(urlEqualTo("/pinned")).withHeader("Host", equalTo("hooks.example.org")));
	}

	@Test
	void aRefusedAddressMovesOnToTheNextValidatedAddressAndNeverToAnUnvalidatedOne() {
		receiver.stubFor(post("/multi").willReturn(aResponse().withStatus(200)));
		DestinationPolicy twoAddresses = new DestinationPolicy(List.of("hooks.example.org"),
				host -> List.of(InetAddress.getByName("93.184.215.14"), InetAddress.getByName("93.184.215.15")));
		CloseableHttpClient firstRefused = WebhookTestServer.redirectingHttpClient(WebhookTestServer.trustingSslContext(),
				receiver::getHttpsPort, Duration.ofSeconds(2), connections::add,
				address -> address.getAddress().getHostAddress().equals("93.184.215.14"));
		HttpsWebhookSender multi = new HttpsWebhookSender(firstRefused, json, twoAddresses, cipher, new WebhookSigner(),
				Duration.ofSeconds(5), Clock.fixed(NOW, ZoneOffset.UTC));

		AttemptResult result = multi.send(delivery(url("/multi")));

		assertThat(result.outcome()).isEqualTo(DeliveryOutcome.SUCCESS);
		assertThat(connections).extracting(a -> a.getAddress().getHostAddress())
			.containsExactly("93.184.215.14", "93.184.215.15");
	}

	@Test
	void whenEveryValidatedAddressIsRefusedTheAttemptIsAConnectionError() {
		CloseableHttpClient allRefused = WebhookTestServer.redirectingHttpClient(WebhookTestServer.trustingSslContext(),
				receiver::getHttpsPort, Duration.ofSeconds(2), connections::add, address -> true);

		AttemptResult result = sender(allRefused, Duration.ofSeconds(5)).send(delivery(url("/none")));

		assertThat(result.error()).isEqualTo(TransportError.CONNECTION);
		assertThat(connections).hasSize(1);
	}

	@Test
	void certificateHostNameVerificationStillAppliesToThePinnedConnection() {
		receiver.stubFor(post("/hook").willReturn(aResponse().withStatus(200)));

		// mismatch.example.org is allowlisted and public, but the test certificate does not list it (it lists
		// hooks.example.org): the connection goes to the pinned address and the TLS handshake must fail.
		AttemptResult mismatch = sender(trusting, Duration.ofSeconds(5)).send(delivery("https://mismatch.example.org/hook"));
		AttemptResult matching = sender(trusting, Duration.ofSeconds(5)).send(delivery(url("/hook")));

		assertThat(mismatch.error()).isEqualTo(TransportError.TLS);
		assertThat(mismatch.outcome()).isEqualTo(DeliveryOutcome.PERMANENT);
		assertThat(matching.outcome()).isEqualTo(DeliveryOutcome.SUCCESS);
		receiver.verify(1, postRequestedFor(urlEqualTo("/hook")));
	}

	@Test
	void theProductionClientCanNeverResolveANameItself() {
		org.assertj.core.api.Assertions.assertThatExceptionOfType(UnknownHostException.class)
			.isThrownBy(() -> WebhookHttpClientConfiguration.neverResolve().resolve("hooks.example.org"));
		// The production bean builds with the JVM trust store and the default TLS strategy (host name check on).
		try (CloseableHttpClient production = new WebhookHttpClientConfiguration()
			.webhookHttpClient(Duration.ofSeconds(2))) {
			assertThat(production).isNotNull();
		}
		catch (java.io.IOException ex) {
			throw new IllegalStateException(ex);
		}
	}

	@Test
	void aReceiverThatDripsTheResponseForeverEndsTheAttemptAtTheTotalDeadlineAndReleasesEverything() {
		receiver.stubFor(post("/after").willReturn(aResponse().withStatus(200)));
		try (DrippingTlsReceiver drip = DrippingTlsReceiver.draggingTheHead(200)) {
			AtomicInteger port = new AtomicInteger(drip.port());
			PoolingHttpClientConnectionManager pool = WebhookTestServer.redirectingConnectionManager(
					WebhookTestServer.trustingSslContext(), port::get, Duration.ofSeconds(2), connections::add,
					address -> false);
			// The production deadline (10 s), real time: one byte every 200 ms never trips a per-read timeout.
			HttpsWebhookSender dripped = sender(WebhookTestServer.httpClient(pool), Duration.ofSeconds(10));

			long started = System.nanoTime();
			AttemptResult result = assertTimeoutPreemptively(Duration.ofSeconds(20),
					() -> dripped.send(delivery(url("/drip"))));
			Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

			// Not early, and not stuck: about 10 s, with a clear margin above and a floor below.
			assertThat(elapsed).isBetween(Duration.ofMillis(9_800), Duration.ofSeconds(12));
			assertThat(result.error()).isEqualTo(TransportError.TIMEOUT);
			assertThat(result.errorType()).isEqualTo("timeout");
			assertThat(result.outcome()).isEqualTo(DeliveryOutcome.RETRYABLE);
			assertThat(result.latencyMs()).isBetween(9_800, 12_000);
			// Nothing leaked: no leased connection, no waiting lease, and the receiver saw the client go away.
			assertThat(pool.getTotalStats().getLeased()).isZero();
			assertThat(pool.getTotalStats().getPending()).isZero();
			await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(drip.droppedConnections()).isEqualTo(1));
			// The same client, pool and sender still work right away.
			port.set(receiver.getHttpsPort());
			AttemptResult next = assertTimeoutPreemptively(Duration.ofSeconds(5),
					() -> dripped.send(delivery(url("/after"))));
			assertThat(next.outcome()).isEqualTo(DeliveryOutcome.SUCCESS);
		}
	}

	@Test
	void anEndlessResponseBodyIsNeverReadSoTheStatusIsReturnedAtOnceAndTheConnectionIsReleased() {
		try (DrippingTlsReceiver drip = DrippingTlsReceiver.draggingTheBody(100)) {
			PoolingHttpClientConnectionManager pool = WebhookTestServer.redirectingConnectionManager(
					WebhookTestServer.trustingSslContext(), drip::port, Duration.ofSeconds(2), connections::add,
					address -> false);

			long started = System.nanoTime();
			AttemptResult result = assertTimeoutPreemptively(Duration.ofSeconds(8),
					() -> sender(WebhookTestServer.httpClient(pool), Duration.ofSeconds(10)).send(delivery(url("/body"))));

			assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
			assertThat(result.httpStatus()).isEqualTo(200);
			assertThat(pool.getTotalStats().getLeased()).isZero();
			await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(drip.droppedConnections()).isEqualTo(1));
		}
	}

	@Test
	void aStatusThatArrivesAfterTheDeadlineIsRecordedAsATimeoutNeverAsADelivery() {
		// A transport that ignores the abort and answers 200 after the deadline.
		CloseableHttpClient ignoresAbort = new CloseableHttpClient() {
			@Override
			public org.apache.hc.core5.http.ClassicHttpResponse executeOpen(HttpHost target, ClassicHttpRequest request,
					HttpContext context) {
				try {
					Thread.sleep(700);
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
				}
				return new BasicClassicHttpResponse(200);
			}

			@Override
			protected CloseableHttpResponse doExecute(HttpHost target, ClassicHttpRequest request, HttpContext context) {
				throw new UnsupportedOperationException();
			}

			@Override
			public void close() {
			}

			@Override
			public void close(CloseMode closeMode) {
			}
		};

		AttemptResult result = sender(ignoresAbort, Duration.ofMillis(300)).send(delivery(url("/late")));

		assertThat(result.httpStatus()).isNull();
		assertThat(result.error()).isEqualTo(TransportError.TIMEOUT);
		assertThat(result.outcome()).isEqualTo(DeliveryOutcome.RETRYABLE);
	}

	@Test
	void theSignatureMatchesAFixedTestVector() {
		receiver.stubFor(post("/vector").willReturn(aResponse().withStatus(200)));

		sender(trusting, Duration.ofSeconds(5)).send(delivery(url("/vector"), "test-secret"));

		Request request = receiver.findAll(postRequestedFor(urlEqualTo("/vector"))).getFirst();
		assertThat(request.getBodyAsString()).isEqualTo(VECTOR_BODY);
		assertThat(request.getHeader("Cobre-Signature")).isEqualTo("t=1767225600,v1=" + VECTOR_HMAC);
	}

	@Test
	void theReceiverVerifiesTheSignatureWithTheKnownSecret() {
		receiver.stubFor(post("/signed").andMatching(request -> MatchResult.of(verifies(request, "whsec_known")))
			.willReturn(aResponse().withStatus(200)));
		receiver.stubFor(post("/signed").atPriority(10).willReturn(aResponse().withStatus(401)));

		AttemptResult result = sender(trusting, Duration.ofSeconds(5)).send(delivery(url("/signed"), "whsec_known"));

		assertThat(result.httpStatus()).isEqualTo(200);
		assertThat(result.outcome()).isEqualTo(DeliveryOutcome.SUCCESS);
	}

	@Test
	void aWrongSecretOrAnUnsignedRequestIsNeverAcceptedByTheReceiver() {
		receiver.stubFor(post("/strict").andMatching(request -> MatchResult.of(verifies(request, "whsec_known")))
			.willReturn(aResponse().withStatus(200)));
		receiver.stubFor(post("/strict").atPriority(10).willReturn(aResponse().withStatus(401)));

		AttemptResult wrongSecret = sender(trusting, Duration.ofSeconds(5)).send(delivery(url("/strict"), "whsec_other"));
		AttemptResult unsigned = sender(trusting, Duration.ofSeconds(5)).send(delivery(url("/strict")));

		assertThat(wrongSecret.httpStatus()).isEqualTo(401);
		assertThat(wrongSecret.outcome()).isEqualTo(DeliveryOutcome.PERMANENT);
		assertThat(unsigned.httpStatus()).isEqualTo(401);
	}

	@Test
	void aDemoSeedDeliveryCarriesASignatureTheDemoHostCanVerify() {
		// The seeder encrypts the externally configured secret with the subscription id as associated data.
		String demoSecret = "whsec_demo-only";
		byte[] seeded = cipher.encrypt(demoSecret.getBytes(StandardCharsets.UTF_8), "SUB-CLIENT001");
		Delivery seedDelivery = new Delivery(new NotificationEventId("N1"), "E1", "credit_deposit", "content",
				url("/demo"), NOW, DeliveryStatus.PENDING, 1, 0, null, null, null, 0, "SUB-CLIENT001", seeded);
		receiver.stubFor(post("/demo").andMatching(request -> MatchResult.of(verifies(request, demoSecret)))
			.willReturn(aResponse().withStatus(200)));
		receiver.stubFor(post("/demo").atPriority(10).willReturn(aResponse().withStatus(401)));

		AttemptResult result = sender(trusting, Duration.ofSeconds(5)).send(seedDelivery);

		assertThat(result.outcome()).isEqualTo(DeliveryOutcome.SUCCESS);
		receiver.verify(1, postRequestedFor(urlEqualTo("/demo")).withHeader("Cobre-Signature", matching("t=[0-9]+,v1=[0-9a-f]{64}")));
	}

	@Test
	void aSubscriptionWithoutASecretFailsAsPermanentAndNothingIsSent() {
		receiver.stubFor(post("/plain").willReturn(aResponse().withStatus(200)));

		AttemptResult result = sender(trusting, Duration.ofSeconds(5)).send(unsignedDelivery(url("/plain")));

		assertThat(result.error()).isEqualTo(TransportError.MISSING_SECRET);
		assertThat(result.errorType()).isEqualTo("missing_secret");
		assertThat(result.outcome()).isEqualTo(DeliveryOutcome.PERMANENT);
		assertThat(receiver.getAllServeEvents()).isEmpty();
	}

	@Test
	void aSecretThatCannotBeDecryptedIsAPermanentFailureAndNothingIsSent() {
		receiver.stubFor(post("/broken").willReturn(aResponse().withStatus(200)));
		// Encrypted for another subscription: the associated data does not match, like a copied or tampered row.
		byte[] foreign = cipher.encrypt("whsec_known".getBytes(StandardCharsets.UTF_8), "SUB-OTHER");
		Delivery broken = new Delivery(new NotificationEventId("N1"), "E1", "credit_deposit", "content",
				url("/broken"), NOW, DeliveryStatus.PENDING, 1, 0, null, null, null, 0, "SUB1", foreign);

		AttemptResult result = sender(trusting, Duration.ofSeconds(5)).send(broken);

		assertThat(result.error()).isEqualTo(TransportError.SIGNING_ERROR);
		assertThat(result.outcome()).isEqualTo(DeliveryOutcome.PERMANENT);
		assertThat(receiver.getAllServeEvents()).isEmpty();
	}

	@Test
	void everyAttemptAndEveryRoundCarriesTheSameIdTheSameBodyAndItsOwnSignatureTime() {
		receiver.stubFor(post("/rounds").andMatching(request -> MatchResult.of(verifies(request, "whsec_known")))
			.willReturn(aResponse().withStatus(503)));
		receiver.stubFor(post("/rounds").atPriority(10).willReturn(aResponse().withStatus(401)));
		MutableClock clock = new MutableClock(NOW);
		HttpsWebhookSender sender = new HttpsWebhookSender(trusting, json, policy, cipher, new WebhookSigner(),
				Duration.ofSeconds(5), clock);
		Delivery firstAttempt = delivery(url("/rounds"), "whsec_known");

		sender.send(firstAttempt);
		// The backoff is longer than the 5-minute tolerance: reusing the first t would be rejected.
		clock.set(NOW.plus(Duration.ofMinutes(8)));
		sender.send(withProgress(firstAttempt, 1, 1));
		// A replay of the same notification event, a day later, in a new round.
		clock.set(NOW.plus(Duration.ofDays(1)));
		sender.send(withProgress(firstAttempt, 2, 0));

		List<Request> requests = receiver.findAll(postRequestedFor(urlEqualTo("/rounds")))
			.stream()
			.map(event -> (Request) event)
			.toList();
		assertThat(requests).hasSize(3);
		// A 401 would mean the signature did not verify against its own t.
		assertThat(receiver.getAllServeEvents()).allSatisfy(event -> assertThat(event.getResponse().getStatus()).isEqualTo(503));
		assertThat(requests).extracting(request -> request.getHeader("Cobre-Notification-Id")).containsOnly("N1");
		assertThat(requests).extracting(Request::getBody).containsOnly(requests.getFirst().getBody());
		assertThat(new String(requests.getFirst().getBody(), StandardCharsets.UTF_8)).isEqualTo(VECTOR_BODY);
		assertThat(requests).extracting(HttpsWebhookSenderTests::signedAt)
			.containsExactly(NOW.getEpochSecond(), NOW.plus(Duration.ofMinutes(8)).getEpochSecond(),
					NOW.plus(Duration.ofDays(1)).getEpochSecond());
	}

	@Test
	void aRetriedAttemptSignatureVerifiesIndependentlyWithinTheToleranceAtItsSendTime() {
		receiver.stubFor(post("/retry").willReturn(aResponse().withStatus(503)));
		MutableClock clock = new MutableClock(NOW.plus(Duration.ofMinutes(8)));
		HttpsWebhookSender sender = new HttpsWebhookSender(trusting, json, policy, cipher, new WebhookSigner(),
				Duration.ofSeconds(5), clock);

		sender.send(withProgress(delivery(url("/retry"), "whsec_known"), 1, 1));

		Request request = receiver.findAll(postRequestedFor(urlEqualTo("/retry"))).getFirst();
		assertThat(verifies(request, "whsec_known")).isTrue();
		assertThat(verifies(request, "whsec_other")).isFalse();
		assertThat(Duration.between(Instant.ofEpochSecond(signedAt(request)), clock.instant()).abs())
			.isLessThanOrEqualTo(Duration.ofMinutes(5));
	}

	private static long signedAt(Request request) {
		String header = request.getHeader("Cobre-Signature");
		return Long.parseLong(header.substring(2, header.indexOf(',')));
	}

	/** The same notification event after earlier attempts or in a later round, as the repository would load it. */
	private static Delivery withProgress(Delivery d, int round, int attemptsInRound) {
		return new Delivery(d.id(), d.eventId(), d.eventType(), d.content(), d.destinationUrl(), d.createdAt(),
				DeliveryStatus.RETRYING, round, attemptsInRound, null, null, "http_503", d.version() + 1,
				d.subscriptionId(), d.secretCiphertext());
	}

	/** A clock the test moves between attempts; the sender takes {@code t} from it at send time. */
	private static final class MutableClock extends Clock {

		private Instant now;

		MutableClock(Instant now) {
			this.now = now;
		}

		void set(Instant now) {
			this.now = now;
		}

		@Override
		public ZoneOffset getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(java.time.ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now;
		}

	}

	/**
	 * What a real receiver does: recompute HMAC-SHA256 of "t.body" with its secret and compare in constant time.
	 */
	private static boolean verifies(Request request, String secret) {
		String header = request.getHeader("Cobre-Signature");
		if (header == null || !header.matches("t=\\d+,v1=[0-9a-f]{64}")) {
			return false;
		}
		String t = header.substring(2, header.indexOf(','));
		String v1 = header.substring(header.indexOf("v1=") + 3);
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			mac.update((t + ".").getBytes(StandardCharsets.UTF_8));
			byte[] expected = mac.doFinal(request.getBody());
			return MessageDigest.isEqual(expected, HexFormat.of().parseHex(v1));
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException(ex);
		}
	}

	/**
	 * A policy-valid URL: the test client redirects it to the local receiver, keeping the path.
	 */
	@Test
	void aRetryAfterInSecondsOnA429IsCarried() {
		receiver.stubFor(post("/ra").willReturn(aResponse().withStatus(429).withHeader("Retry-After", "120")));

		AttemptResult result = sender(trusting, Duration.ofSeconds(5)).send(delivery(url("/ra")));

		assertThat(result.outcome()).isEqualTo(DeliveryOutcome.RETRYABLE);
		assertThat(result.retryAfter()).isEqualTo(Duration.ofSeconds(120));
	}

	@Test
	void aRetryAfterHttpDateOnA503IsCarriedRelativeToTheAttempt() {
		receiver.stubFor(post("/ra").willReturn(
				aResponse().withStatus(503).withHeader("Retry-After", "Thu, 01 Jan 2026 00:02:00 GMT")));

		AttemptResult result = sender(trusting, Duration.ofSeconds(5)).send(delivery(url("/ra")));

		assertThat(result.retryAfter()).isEqualTo(Duration.ofSeconds(120));
	}

	@Test
	void aPastRetryAfterDateOnA503UsesTheBackoff() {
		receiver.stubFor(post("/ra").willReturn(
				aResponse().withStatus(503).withHeader("Retry-After", "Wed, 31 Dec 2025 23:59:00 GMT")));

		AttemptResult result = sender(trusting, Duration.ofSeconds(5)).send(delivery(url("/ra")));

		assertThat(result.outcome()).isEqualTo(DeliveryOutcome.RETRYABLE);
		assertThat(result.retryAfter()).isNull();
	}

	@Test
	void anInvalidRetryAfterFallsBackToTheBackoff() {
		for (String invalid : List.of("soon", "-5", "1.5", "", "12 seconds")) {
			receiver.stubFor(post("/ra").willReturn(aResponse().withStatus(429).withHeader("Retry-After", invalid)));

			AttemptResult result = sender(trusting, Duration.ofSeconds(5)).send(delivery(url("/ra")));

			assertThat(result.retryAfter()).as("Retry-After '%s'", invalid).isNull();
		}
	}

	@Test
	void retryAfterOnAnyOtherStatusIsIgnored() {
		for (int status : List.of(500, 502, 408)) {
			receiver.stubFor(post("/ra").willReturn(aResponse().withStatus(status).withHeader("Retry-After", "120")));

			AttemptResult result = sender(trusting, Duration.ofSeconds(5)).send(delivery(url("/ra")));

			assertThat(result.outcome()).isEqualTo(DeliveryOutcome.RETRYABLE);
			assertThat(result.retryAfter()).as("status %d", status).isNull();
		}
	}

	@Test
	void a429WithoutRetryAfterCarriesNoDelay() {
		receiver.stubFor(post("/ra").willReturn(aResponse().withStatus(429)));

		AttemptResult result = sender(trusting, Duration.ofSeconds(5)).send(delivery(url("/ra")));

		assertThat(result.retryAfter()).isNull();
	}

	@Test
	void aRetryAfterOfZeroOnA429UsesTheBackoff() {
		receiver.stubFor(post("/ra").willReturn(aResponse().withStatus(429).withHeader("Retry-After", "0")));

		AttemptResult result = sender(trusting, Duration.ofSeconds(5)).send(delivery(url("/ra")));

		assertThat(result.outcome()).isEqualTo(DeliveryOutcome.RETRYABLE);
		assertThat(result.retryAfter()).isNull();
	}

	@Test
	void everyRfc9110HttpDateFormatIsAccepted() {
		Duration expected = Duration.ofSeconds(120);
		assertThat(HttpsWebhookSender.parseRetryAfter("Thu, 01 Jan 2026 00:02:00 GMT", NOW)).isEqualTo(expected);
		assertThat(HttpsWebhookSender.parseRetryAfter("Thursday, 01-Jan-26 00:02:00 GMT", NOW)).isEqualTo(expected);
		assertThat(HttpsWebhookSender.parseRetryAfter("Thu Jan  1 00:02:00 2026", NOW)).isEqualTo(expected);
	}

	@Test
	void aPastDateInAnyRfc9110FormatUsesTheBackoff() {
		assertThat(HttpsWebhookSender.parseRetryAfter("Wed, 31 Dec 2025 23:59:00 GMT", NOW)).isNull();
		assertThat(HttpsWebhookSender.parseRetryAfter("Wednesday, 31-Dec-25 23:59:00 GMT", NOW))
			.isNull();
		assertThat(HttpsWebhookSender.parseRetryAfter("Wed Dec 31 23:59:00 2025", NOW)).isNull();
	}

	@Test
	void aDateEqualToNowAndAValueOfZeroUseTheBackoff() {
		assertThat(HttpsWebhookSender.parseRetryAfter("Thu, 01 Jan 2026 00:00:00 GMT", NOW)).isNull();
		assertThat(HttpsWebhookSender.parseRetryAfter("Thursday, 01-Jan-26 00:00:00 GMT", NOW)).isNull();
		assertThat(HttpsWebhookSender.parseRetryAfter("Thu Jan  1 00:00:00 2026", NOW)).isNull();
		assertThat(HttpsWebhookSender.parseRetryAfter("0", NOW)).isNull();
	}

	@Test
	void anInvalidHttpDateInAnyFormatFallsBackToBackoff() {
		assertThat(HttpsWebhookSender.parseRetryAfter("Thu, 32 Jan 2026 00:02:00 GMT", NOW)).isNull();
		assertThat(HttpsWebhookSender.parseRetryAfter("Thursday, 01-Foo-26 00:02:00 GMT", NOW)).isNull();
		assertThat(HttpsWebhookSender.parseRetryAfter("Thu Jan  1 25:02:00 2026", NOW)).isNull();
		assertThat(HttpsWebhookSender.parseRetryAfter("soon", NOW)).isNull();
	}

	@Test
	void aHugeRetryAfterIsStillAValidDelayForTheServiceToCap() {
		assertThat(HttpsWebhookSender.parseRetryAfter("99999999999999999999999", NOW)).isNotNull();
		assertThat(HttpsWebhookSender.parseRetryAfter("0", NOW)).isNull();
	}

	private static String url(String path) {
		return "https://hooks.example.org" + path;
	}

	/** A signed delivery with a default secret; a delivery without a secret is never sent. */
	private Delivery delivery(String destination) {
		return delivery(destination, "whsec_default");
	}

	private static Delivery unsignedDelivery(String destination) {
		return new Delivery(new NotificationEventId("N1"), "E1", "credit_deposit", "Deposit of $10 \"received\"",
				destination, NOW, DeliveryStatus.PENDING, 1, 0, null, null, null, 0);
	}

	private Delivery delivery(String destination, String secret) {
		return new Delivery(new NotificationEventId("N1"), "E1", "credit_deposit", "Deposit of $10 \"received\"",
				destination, NOW, DeliveryStatus.PENDING, 1, 0, null, null, null, 0, "SUB1",
				cipher.encrypt(secret.getBytes(StandardCharsets.UTF_8), "SUB1"));
	}

}
