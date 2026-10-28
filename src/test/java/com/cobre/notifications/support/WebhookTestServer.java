package com.cobre.notifications.support;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.time.Duration;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

/**
 * A WireMock receiver that speaks HTTPS with a test-only certificate, and the matching client trust.
 *
 * <p>
 * The certificate is self-signed for {@code localhost} / {@code 127.0.0.1} and lives under
 * {@code src/test/resources/tls}. Only {@link #trustingHttpClient(Duration)} trusts it, and only inside tests: the
 * production {@code HttpClient} is untouched, still verifies the chain and the host name, and rejects this certificate
 * (see {@code HttpsWebhookSenderTests}).
 */
public final class WebhookTestServer {

	private static final Path KEYSTORE = Path.of("src/test/resources/tls/webhook-test-keystore.p12");

	private static final Path TRUSTSTORE = Path.of("src/test/resources/tls/webhook-test-truststore.p12");

	private static final String PASSWORD = "changeit";

	private WebhookTestServer() {
	}

	public static WireMockExtension extension() {
		return WireMockExtension.newInstance()
			.options(wireMockConfig().dynamicPort()
				.dynamicHttpsPort()
				.keystorePath(KEYSTORE.toString())
				.keystoreType("PKCS12")
				.keystorePassword(PASSWORD)
				.keyManagerPassword(PASSWORD))
			.build();
	}

	/**
	 * The production client settings (no redirects, 2 s connect timeout) plus trust for the test certificate.
	 */
	public static HttpClient trustingHttpClient(Duration connectTimeout) {
		try (InputStream in = Files.newInputStream(TRUSTSTORE)) {
			KeyStore trustStore = KeyStore.getInstance("PKCS12");
			trustStore.load(in, PASSWORD.toCharArray());
			TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
			trust.init(trustStore);
			SSLContext tls = SSLContext.getInstance("TLS");
			tls.init(null, trust.getTrustManagers(), null);
			return HttpClient.newBuilder()
				.sslContext(tls)
				.followRedirects(HttpClient.Redirect.NEVER)
				.connectTimeout(connectTimeout)
				.build();
		}
		catch (IOException | GeneralSecurityException ex) {
			throw new IllegalStateException("Cannot load the test trust store", ex);
		}
	}

}
