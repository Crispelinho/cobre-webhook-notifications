package com.cobre.notifications.notifications.adapters.out.http;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import javax.net.ssl.SSLException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.cobre.notifications.notifications.application.port.out.WebhookSender;
import com.cobre.notifications.notifications.domain.AttemptResult;
import com.cobre.notifications.notifications.domain.Delivery;
import com.cobre.notifications.notifications.domain.TransportError;

import tools.jackson.databind.json.JsonMapper;

/**
 * Sends the webhook with the JDK {@link HttpClient}: HTTPS only, redirects never followed (a {@code 3xx} comes back as
 * a plain status) and a hard request timeout. The call blocks, which is cheap on the virtual threads that run the
 * Kafka listeners. Nothing from the request or response is logged: destinations and content stay out of the logs.
 */
@Component
class HttpsWebhookSender implements WebhookSender {

	private static final Logger log = LoggerFactory.getLogger(HttpsWebhookSender.class);

	private final HttpClient client;

	private final JsonMapper json;

	private final Clock clock = Clock.systemUTC();

	private final Duration requestTimeout;

	HttpsWebhookSender(HttpClient webhookHttpClient, JsonMapper json,
			@Value("${app.delivery.http.request-timeout:PT10S}") Duration requestTimeout) {
		this.client = webhookHttpClient;
		this.json = json;
		this.requestTimeout = requestTimeout;
	}

	@Override
	public AttemptResult send(Delivery delivery) {
		Instant attemptedAt = clock.instant();
		long started = System.nanoTime();
		URI destination = parse(delivery.destinationUrl());
		if (destination == null || !"https".equalsIgnoreCase(destination.getScheme())
				|| destination.getHost() == null) {
			return AttemptResult.failure(attemptedAt, TransportError.INVALID_DESTINATION, 0);
		}
		HttpRequest request = HttpRequest.newBuilder(destination)
			.timeout(requestTimeout)
			.header("Content-Type", "application/json")
			.header("Cobre-Notification-Id", delivery.id().value())
			.POST(HttpRequest.BodyPublishers.ofString(WebhookBody.from(delivery).toJson(json)))
			.build();
		try {
			HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
			return AttemptResult.response(attemptedAt, response.statusCode(), elapsedMs(started));
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			// The consumer is shutting down: let the message be redelivered rather than record a fake failure.
			throw new IllegalStateException("Interrupted while sending webhook", ex);
		}
		catch (IOException ex) {
			TransportError error = classify(ex);
			log.debug("Webhook attempt for notification event {} failed: {}", delivery.id().value(), error);
			return AttemptResult.failure(attemptedAt, error, elapsedMs(started));
		}
	}

	private static URI parse(String url) {
		try {
			return URI.create(url);
		}
		catch (IllegalArgumentException ex) {
			return null;
		}
	}

	private static TransportError classify(IOException ex) {
		for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
			if (cause instanceof HttpTimeoutException) {
				return TransportError.TIMEOUT;
			}
			if (cause instanceof SSLException) {
				return TransportError.TLS;
			}
			if (cause instanceof ConnectException) {
				return TransportError.CONNECTION;
			}
		}
		return TransportError.CONNECTION;
	}

	private static int elapsedMs(long startedNanos) {
		return (int) Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
	}

}
