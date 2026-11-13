package com.cobre.notifications.support;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.time.Duration;
import java.util.function.IntSupplier;
import java.util.function.Predicate;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.DefaultHttpClientConnectionOperator;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.ssl.DefaultClientTlsStrategy;
import org.apache.hc.client5.http.ssl.TlsSocketStrategy;
import org.apache.hc.core5.http.config.RegistryBuilder;
import org.apache.hc.core5.pool.PoolConcurrencyPolicy;
import org.apache.hc.core5.pool.PoolReusePolicy;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;

/**
 * A WireMock receiver that speaks HTTPS with a test-only certificate, and the matching client trust.
 *
 * <p>
 * The certificate is self-signed for {@code localhost}, {@code 127.0.0.1}, {@code hooks.example.org} and
 * {@code webhook.example.com} and lives under {@code src/test/resources/tls}. Only {@link #trustingSslContext()} trusts
 * it, and only inside tests: the production {@code HttpClient} is untouched, still verifies the chain and the host
 * name, and rejects this certificate (see {@code HttpsWebhookSenderTests}). The host name check stays ON in the test
 * clients too: a certificate that does not list the host is rejected.
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
	 * An SSL context that trusts only the test certificate.
	 */
	public static SSLContext trustingSslContext() {
		try (InputStream in = Files.newInputStream(TRUSTSTORE)) {
			KeyStore trustStore = KeyStore.getInstance("PKCS12");
			trustStore.load(in, PASSWORD.toCharArray());
			TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
			trust.init(trustStore);
			SSLContext tls = SSLContext.getInstance("TLS");
			tls.init(null, trust.getTrustManagers(), null);
			return tls;
		}
		catch (IOException | GeneralSecurityException ex) {
			throw new IllegalStateException("Cannot load the test trust store", ex);
		}
	}

	/**
	 * The JVM default trust, as production uses: it does not trust the test certificate.
	 */
	public static SSLContext defaultSslContext() {
		try {
			return SSLContext.getDefault();
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException(ex);
		}
	}

	/**
	 * What the client was asked to connect to, so a test can assert it is the validated address.
	 */
	public interface ConnectionLog {

		void connected(InetSocketAddress requested);

	}

	/**
	 * A client with the production settings (no redirects, no retries, no resolver: any name lookup fails, so a
	 * connection can only go to the pinned address it is given, and the host name check stays ON) whose sockets are
	 * redirected to {@code localhost:<httpsPort>}. The address each connection was <em>asked</em> to open is reported
	 * to {@code log} before the redirect, and {@code refuse} can make an address fail like a refused connection.
	 * <p>
	 * Production code has no such seam: the destination policy only accepts public addresses on port 443, so a test
	 * cannot reach a local receiver without redirecting at the socket level.
	 */
	public static CloseableHttpClient redirectingHttpClient(SSLContext tls, IntSupplier httpsPort, Duration connectTimeout,
			ConnectionLog log, Predicate<InetSocketAddress> refuse) {
		return httpClient(redirectingConnectionManager(tls, httpsPort, connectTimeout, log, refuse));
	}

	/**
	 * The client settings shared by every test client.
	 */
	public static CloseableHttpClient httpClient(PoolingHttpClientConnectionManager connections) {
		return HttpClients.custom()
			.setConnectionManager(connections)
			.disableRedirectHandling()
			.disableAutomaticRetries()
			.disableCookieManagement()
			.build();
	}

	/**
	 * The connection manager behind {@link #redirectingHttpClient}; tests that need pool statistics (leased
	 * connections) build the client from it with {@link #httpClient}.
	 */
	public static PoolingHttpClientConnectionManager redirectingConnectionManager(SSLContext tls, IntSupplier httpsPort,
			Duration connectTimeout, ConnectionLog log, Predicate<InetSocketAddress> refuse) {
		DnsResolver noLookups = new DnsResolver() {
			@Override
			public InetAddress[] resolve(String host) throws UnknownHostException {
				throw new UnknownHostException("test client: name lookups are not allowed (" + host + ")");
			}

			@Override
			public String resolveCanonicalHostname(String host) throws UnknownHostException {
				throw new UnknownHostException(host);
			}
		};
		DefaultHttpClientConnectionOperator operator = new DefaultHttpClientConnectionOperator(
				proxy -> new RedirectingSocket(httpsPort, log, refuse), null, noLookups,
				RegistryBuilder.<TlsSocketStrategy>create().register("https", new DefaultClientTlsStrategy(tls)).build());
		PoolingHttpClientConnectionManager connections = new PoolingHttpClientConnectionManager(operator,
				PoolConcurrencyPolicy.STRICT, PoolReusePolicy.LIFO, TimeValue.NEG_ONE_MILLISECOND, null);
		connections.setDefaultConnectionConfig(
				ConnectionConfig.custom().setConnectTimeout(Timeout.ofMilliseconds(connectTimeout.toMillis())).build());
		return connections;
	}

	/**
	 * The client above trusting the test certificate, redirecting to the local receiver.
	 */
	public static CloseableHttpClient trustingHttpClient(IntSupplier httpsPort) {
		return redirectingHttpClient(trustingSslContext(), httpsPort, Duration.ofSeconds(2), requested -> {
		}, address -> false);
	}

	private static final class RedirectingSocket extends Socket {

		private final IntSupplier httpsPort;

		private final ConnectionLog log;

		private final Predicate<InetSocketAddress> refuse;

		private RedirectingSocket(IntSupplier httpsPort, ConnectionLog log, Predicate<InetSocketAddress> refuse) {
			this.httpsPort = httpsPort;
			this.log = log;
			this.refuse = refuse;
		}

		@Override
		public void connect(SocketAddress endpoint, int timeout) throws IOException {
			InetSocketAddress requested = (InetSocketAddress) endpoint;
			log.connected(requested);
			if (refuse.test(requested)) {
				throw new ConnectException("refused by the test");
			}
			super.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), httpsPort.getAsInt()), timeout);
		}

	}

}
