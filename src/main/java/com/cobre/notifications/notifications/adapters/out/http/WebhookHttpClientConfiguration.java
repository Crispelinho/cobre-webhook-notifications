package com.cobre.notifications.notifications.adapters.out.http;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The HTTP client for webhooks. It uses the JVM trust store; tests replace the bean to trust a test certificate.
 */
@Configuration(proxyBeanMethods = false)
class WebhookHttpClientConfiguration {

	@Bean
	HttpClient webhookHttpClient(@Value("${app.delivery.http.connect-timeout:PT2S}") Duration connectTimeout) {
		return HttpClient.newBuilder()
			.followRedirects(HttpClient.Redirect.NEVER)
			.connectTimeout(connectTimeout)
			.build();
	}

}
