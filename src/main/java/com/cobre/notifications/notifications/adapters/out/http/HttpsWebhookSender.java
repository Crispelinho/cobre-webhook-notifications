package com.cobre.notifications.notifications.adapters.out.http;

import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.SSLException;

import jakarta.annotation.PreDestroy;

import org.apache.hc.client5.http.ConnectTimeoutException;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.io.ModalCloseable;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.cobre.notifications.notifications.application.port.out.WebhookSender;
import com.cobre.notifications.notifications.domain.AttemptResult;
import com.cobre.notifications.notifications.domain.Delivery;
import com.cobre.notifications.notifications.domain.TransportError;
import com.cobre.notifications.shared.crypto.SecretCipher;
import com.cobre.notifications.shared.crypto.WebhookSigner;
import com.cobre.notifications.shared.security.DestinationPolicy;
import com.cobre.notifications.shared.security.RejectedDestinationException;

import tools.jackson.databind.json.JsonMapper;

/**
 * Sends the webhook with Apache HttpClient 5: HTTPS only, redirects never followed (a {@code 3xx} comes back as a
 * plain status) and a hard TOTAL deadline per attempt (see {@link #send}). Before every attempt the destination goes through the shared
 * {@link DestinationPolicy} (a rejection is an {@code INVALID_DESTINATION} attempt and no request is sent), the
 * connection goes to the address the policy validated and never to a second DNS answer (see
 * {@link WebhookHttpClientConfiguration}), and when the subscription has a secret the body is signed with it
 * ({@code Cobre-Signature}). The call blocks, which is cheap on the virtual threads that run the Kafka listeners.
 * Nothing from the request or response is logged: destinations and content stay out of the logs.
 */
@Component
class HttpsWebhookSender implements WebhookSender {

	private static final Logger log = LoggerFactory.getLogger(HttpsWebhookSender.class);

	/** The destination policy only accepts the default HTTPS port. */
	private static final int HTTPS_PORT = 443;

	private final CloseableHttpClient client;

	private final JsonMapper json;

	private final DestinationPolicy destinationPolicy;

	private final SecretCipher secretCipher;

	private final WebhookSigner signer;

	private final Clock clock;

	private final Duration requestTimeout;

	/**
	 * One daemon thread that fires the per-request deadlines. A task only flips a flag and aborts the request, so one
	 * thread serves any number of concurrent consumers.
	 */
	private final ScheduledThreadPoolExecutor watchdogs = newWatchdog();

	@Autowired
	HttpsWebhookSender(CloseableHttpClient client, JsonMapper json, DestinationPolicy destinationPolicy,
			SecretCipher secretCipher, WebhookSigner signer,
			@Value("${app.delivery.http.request-timeout:PT10S}") Duration requestTimeout, Clock clock) {
		this.client = client;
		this.json = json;
		this.destinationPolicy = destinationPolicy;
		this.secretCipher = secretCipher;
		this.signer = signer;
		this.requestTimeout = requestTimeout;
		this.clock = clock;
	}

	private static ScheduledThreadPoolExecutor newWatchdog() {
		ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, runnable -> {
			Thread thread = Executors.defaultThreadFactory().newThread(runnable);
			thread.setName("webhook-deadline-watchdog");
			thread.setDaemon(true);
			return thread;
		});
		executor.setRemoveOnCancelPolicy(true);
		return executor;
	}

	@PreDestroy
	void stopWatchdog() {
		watchdogs.shutdownNow();
	}

	@Override
	public AttemptResult send(Delivery delivery) {
		Instant attemptedAt = clock.instant();
		long started = System.nanoTime();
		DestinationPolicy.Destination destination;
		try {
			destination = destinationPolicy.check(delivery.destinationUrl());
		}
		catch (RejectedDestinationException ex) {
			// The reason is fixed text without the URL; the destination itself stays out of the logs.
			log.warn("Destination of notification event {} rejected: {}", delivery.id().value(), ex.getMessage());
			return AttemptResult.failure(attemptedAt, TransportError.INVALID_DESTINATION, elapsedMs(started));
		}
		byte[] body = WebhookBody.from(delivery).toJson(json).getBytes(StandardCharsets.UTF_8);
		HttpPost request = new HttpPost(destination.uri());
		request.setConfig(RequestConfig.custom().setResponseTimeout(Timeout.of(requestTimeout)).build());
		request.setHeader("Content-Type", "application/json");
		request.setHeader("Cobre-Notification-Id", delivery.id().value());
		request.setEntity(new ByteArrayEntity(body, null));
		if (delivery.secretCiphertext() == null) {
			// A subscription without a secret is never delivered unsigned.
			return AttemptResult.failure(attemptedAt, TransportError.MISSING_SECRET, elapsedMs(started));
		}
		String signature = sign(delivery, attemptedAt, body);
		if (signature == null) {
			return AttemptResult.failure(attemptedAt, TransportError.SIGNING_ERROR, elapsedMs(started));
		}
		request.setHeader(WebhookSigner.SIGNATURE_HEADER, signature);
		// The total deadline of this attempt: connect, TLS, sending and receiving the status line. Apache's own timeouts
		// are per operation (a receiver that sends one byte every few seconds never trips them), so a watchdog aborts
		// the request, which closes its socket and releases the connection, when the whole budget is spent.
		AtomicBoolean expired = new AtomicBoolean();
		ScheduledFuture<?> deadline = watchdogs.schedule(() -> {
			expired.set(true);
			request.cancel();
		}, requestTimeout.toNanos(), TimeUnit.NANOSECONDS);
		try {
			return execute(delivery, destination, request, attemptedAt, started, expired);
		}
		finally {
			deadline.cancel(false);
		}
	}

	private AttemptResult execute(Delivery delivery, DestinationPolicy.Destination destination, HttpPost request,
			Instant attemptedAt, long started, AtomicBoolean expired) {
		IOException lastRefused = null;
		// Every address the policy validated, in order. Only a refused connection (nothing was sent) moves on to the
		// next one; any other outcome is the result of this attempt.
		for (InetAddress address : destination.addresses()) {
			if (expired.get()) {
				break;
			}
			// The validated address is the connection target; the host name stays for the Host header, SNI and the
			// certificate check. The client has no resolver, so it cannot look the name up again.
			HttpHost target = new HttpHost(destination.uri().getScheme(), address, destination.host(), HTTPS_PORT);
			try {
				ClassicHttpResponse response = client.executeOpen(target, request, null);
				try {
					// A status that arrives after the deadline (the abort lost the race, or the transport ignored it) is
					// not trusted: it is recorded as a timeout, never as a delivery. The receiver may have processed the
					// request, which is the usual at-least-once trade-off of a retry.
					if (expired.get() || elapsedNanos(started) >= requestTimeout.toNanos()) {
						return AttemptResult.failure(attemptedAt, TransportError.TIMEOUT, elapsedMs(started));
					}
					return AttemptResult.response(attemptedAt, response.getCode(), elapsedMs(started),
							retryAfter(response, attemptedAt));
				}
				finally {
					discard(response);
				}
			}
			catch (ConnectException ex) {
				lastRefused = ex;
			}
			catch (IOException ex) {
				return failed(delivery, attemptedAt, started, expired.get() ? null : ex);
			}
		}
		return failed(delivery, attemptedAt, started, expired.get() ? null : lastRefused);
	}

	/**
	 * The delay of a valid {@code Retry-After}, read on a 429 or 503 only. Null when the header is missing, invalid or
	 * cannot be parsed: the caller then keeps the normal backoff. A past date or 0 is due immediately.
	 */
	private static Duration retryAfter(ClassicHttpResponse response, Instant now) {
		int status = response.getCode();
		if (status != 429 && status != 503) {
			return null;
		}
		org.apache.hc.core5.http.Header header = response.getFirstHeader("Retry-After");
		return header == null ? null : parseRetryAfter(header.getValue(), now);
	}

	/**
	 * Accepts delta-seconds ({@code 120}) and an HTTP-date ({@code Wed, 21 Oct 2026 07:28:00 GMT}). A number too large
	 * for a long is still a valid, huge delay; the service caps it.
	 */
	static Duration parseRetryAfter(String value, Instant now) {
		if (value == null) {
			return null;
		}
		String trimmed = value.trim();
		if (trimmed.matches("[0-9]+")) {
			try {
				return Duration.ofSeconds(Long.parseLong(trimmed));
			}
			catch (NumberFormatException ex) {
				return Duration.ofSeconds(Long.MAX_VALUE);
			}
		}
		Instant at = parseHttpDate(trimmed, now);
		if (at == null) {
			return null;
		}
		// A date at or before now means "due immediately": the same as 0, still through the scheduler.
		return at.isBefore(now) ? Duration.ZERO : Duration.between(now, at);
	}

	/**
	 * The three HTTP-date formats of RFC 9110: IMF-fixdate ({@code Sun, 06 Nov 1994 08:49:37 GMT}), the obsolete RFC 850
	 * form ({@code Sunday, 06-Nov-94 08:49:37 GMT}) and asctime ({@code Sun Nov  6 08:49:37 1994}). All are UTC. A
	 * two-digit year is read as the one within 50 years of {@code now}, as the RFC asks.
	 */
	private static Instant parseHttpDate(String value, Instant now) {
		int baseYear = now.atOffset(ZoneOffset.UTC).getYear() - 50;
		DateTimeFormatter rfc850 = new DateTimeFormatterBuilder().parseCaseSensitive()
			.appendPattern("EEEE, dd-MMM-")
			.appendValueReduced(ChronoField.YEAR, 2, 2, baseYear)
			.appendPattern(" HH:mm:ss 'GMT'")
			.toFormatter(Locale.ENGLISH);
		try {
			return OffsetDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
		}
		catch (DateTimeParseException ignored) {
			// try the next format
		}
		try {
			return LocalDateTime.parse(value, rfc850).toInstant(ZoneOffset.UTC);
		}
		catch (DateTimeParseException ignored) {
			// try the next format
		}
		try {
			return LocalDateTime.parse(value, DateTimeFormatter.ofPattern("EEE MMM ppd HH:mm:ss yyyy", Locale.ENGLISH))
				.toInstant(ZoneOffset.UTC);
		}
		catch (DateTimeParseException | ArithmeticException ex) {
			return null;
		}
	}

	/**
	 * Releases the connection without reading the body: only the status matters, and a slow or endless body must not
	 * hold the consumer (a graceful close would read the rest of it). An empty body keeps the connection reusable.
	 */
	private static void discard(ClassicHttpResponse response) {
		try {
			if (response.getEntity() != null && response.getEntity().getContentLength() != 0
					&& response instanceof ModalCloseable closeable) {
				closeable.close(CloseMode.IMMEDIATE);
			}
			else {
				response.close();
			}
		}
		catch (IOException ex) {
			// The attempt already has its result; a failed close only costs the connection.
			log.debug("Closing the webhook response failed: {}", ex.getClass().getSimpleName());
		}
	}

	/**
	 * @param ex the transport failure, or null when the deadline aborted the request (always a timeout)
	 */
	private AttemptResult failed(Delivery delivery, Instant attemptedAt, long started, IOException ex) {
		if (ex == null) {
			log.debug("Webhook attempt for notification event {} hit the total deadline", delivery.id().value());
			return AttemptResult.failure(attemptedAt, TransportError.TIMEOUT, elapsedMs(started));
		}
		if (Thread.currentThread().isInterrupted()) {
			// The consumer is shutting down: let the message be redelivered rather than record a fake failure.
			throw new IllegalStateException("Interrupted while sending webhook", ex);
		}
		TransportError error = classify(ex);
		log.debug("Webhook attempt for notification event {} failed: {}", delivery.id().value(), error);
		return AttemptResult.failure(attemptedAt, error, elapsedMs(started));
	}

	/**
	 * Decrypts the secret for this one request and signs with the timestamp of this attempt. Returns null when the
	 * secret cannot be decrypted (wrong key, tampered or foreign ciphertext): the request is then never sent unsigned.
	 * The plaintext is a local variable only; nothing about it is logged.
	 */
	private String sign(Delivery delivery, Instant attemptedAt, byte[] body) {
		try {
			String secret = new String(secretCipher.decrypt(delivery.secretCiphertext(), delivery.subscriptionId()),
					StandardCharsets.UTF_8);
			return signer.sign(secret, attemptedAt.getEpochSecond(), body);
		}
		catch (RuntimeException ex) {
			// The exception messages of the cipher are fixed texts, but log only the class to be safe.
			log.error("Cannot sign notification event {}: {}", delivery.id().value(), ex.getClass().getSimpleName());
			return null;
		}
	}

	private static TransportError classify(IOException ex) {
		for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
			if (cause instanceof SocketTimeoutException || cause instanceof ConnectTimeoutException) {
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

	private static long elapsedNanos(long startedNanos) {
		return System.nanoTime() - startedNanos;
	}

	private static int elapsedMs(long startedNanos) {
		return (int) Duration.ofNanos(elapsedNanos(startedNanos)).toMillis();
	}

}
