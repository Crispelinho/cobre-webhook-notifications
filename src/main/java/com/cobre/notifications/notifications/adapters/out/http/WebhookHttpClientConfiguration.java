package com.cobre.notifications.notifications.adapters.out.http;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;

import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.DefaultClientTlsStrategy;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The HTTP client for webhooks (Apache HttpClient 5). It uses the JVM trust store and the default TLS strategy, which
 * verifies the certificate chain and the host name; tests replace the bean to trust a test certificate.
 * <p>
 * The client can never resolve a name itself: {@link #neverResolve()} fails every lookup, so the only way to open a
 * connection is the pinned address that {@link HttpsWebhookSender} passes with each request (the address validated by
 * the destination policy). A hostname that changes its DNS answer between the check and the connection (DNS rebinding)
 * therefore cannot redirect the request.
 */
@Configuration(proxyBeanMethods = false)
class WebhookHttpClientConfiguration {

	@Bean
	CloseableHttpClient webhookHttpClient(@Value("${app.delivery.http.connect-timeout:PT2S}") Duration connectTimeout) {
		PoolingHttpClientConnectionManager connections = PoolingHttpClientConnectionManagerBuilder.create()
			.setDnsResolver(neverResolve())
			.setTlsSocketStrategy(DefaultClientTlsStrategy.createDefault())
			.setDefaultConnectionConfig(ConnectionConfig.custom()
				.setConnectTimeout(Timeout.ofMilliseconds(connectTimeout.toMillis()))
				.build())
			.build();
		return client(connections);
	}

	/**
	 * No redirects (a 3xx comes back as a plain status), no automatic retries (the delivery layer owns retries), no
	 * cookies and no proxy from system properties.
	 */
	static CloseableHttpClient client(PoolingHttpClientConnectionManager connections) {
		return HttpClients.custom()
			.setConnectionManager(connections)
			.disableRedirectHandling()
			.disableAutomaticRetries()
			.disableCookieManagement()
			.disableAuthCaching()
			.build();
	}

	static DnsResolver neverResolve() {
		return new DnsResolver() {
			@Override
			public InetAddress[] resolve(String host) throws UnknownHostException {
				throw new UnknownHostException("webhook connections use the validated address only");
			}

			@Override
			public String resolveCanonicalHostname(String host) throws UnknownHostException {
				throw new UnknownHostException("webhook connections use the validated address only");
			}
		};
	}

}
