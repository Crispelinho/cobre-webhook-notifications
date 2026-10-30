package com.cobre.notifications.shared.security;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Decides whether the service may send a webhook to a URL (OWASP API7, SSRF). A URL is accepted only if it uses
 * {@code https} on the default port 443, carries no user info, its host is in the allowlist and every address the host
 * resolves to is public.
 * <p>
 * Allowlist entries are exact host names; an entry starting with {@code .} (for example {@code .example.org}) also
 * allows that domain and all its subdomains. An empty allowlist rejects every URL.
 * <p>
 * Call {@link #check(String)} when a subscription is registered and again right before each delivery (the allowlist
 * or DNS may have changed since registration, and a subscription can be reactivated later), then connect
 * only to the returned {@link Destination#addresses()} so a DNS change between the check and the request (DNS
 * rebinding) cannot redirect the call to an internal address. Keep {@link Destination#host()} for TLS SNI and the
 * {@code Host} header.
 * <p>
 * The check waits at most the resolve timeout (5 s by default) for DNS, so a slow resolver cannot hold the caller. The
 * lookup runs on its own virtual thread and is interrupted when the wait ends; a blocking system call may keep that
 * thread a little longer.
 */
public final class DestinationPolicy {

	private static final int HTTPS_PORT = 443;

	/** The longest a name lookup may take before the destination counts as a name that does not resolve. */
	public static final Duration DEFAULT_RESOLVE_TIMEOUT = Duration.ofSeconds(5);

	private final List<String> allowedHosts;

	private final HostResolver resolver;

	private final Duration resolveTimeout;

	/** Uses {@link #DEFAULT_RESOLVE_TIMEOUT}. */
	public DestinationPolicy(Collection<String> allowedHosts, HostResolver resolver) {
		this(allowedHosts, resolver, DEFAULT_RESOLVE_TIMEOUT);
	}

	public DestinationPolicy(Collection<String> allowedHosts, HostResolver resolver, Duration resolveTimeout) {
		Objects.requireNonNull(resolveTimeout);
		if (resolveTimeout.isNegative() || resolveTimeout.isZero()) {
			throw new IllegalArgumentException("resolve timeout must be positive");
		}
		this.allowedHosts = allowedHosts.stream()
			.map(String::strip)
			.filter(host -> !host.isEmpty())
			.map(DestinationPolicy::normalize)
			.toList();
		this.resolver = Objects.requireNonNull(resolver);
		this.resolveTimeout = resolveTimeout;
	}

	/**
	 * Returns the validated destination. Throws {@link RejectedDestinationException}, with a reason meant for logs,
	 * when the destination breaks a rule. Throws {@link DestinationResolutionException} when the name does not resolve
	 * or does not resolve within the resolve timeout: a transient failure, not a rejection. The reason should not be
	 * shown to API callers: it would reveal how internal names resolve.
	 */
	public Destination check(String url) {
		URI uri = parse(url);
		if (!"https".equalsIgnoreCase(uri.getScheme())) {
			throw new RejectedDestinationException("only https is allowed");
		}
		if (uri.getPort() != -1 && uri.getPort() != HTTPS_PORT) {
			throw new RejectedDestinationException("only port 443 is allowed");
		}
		if (uri.getRawUserInfo() != null) {
			throw new RejectedDestinationException("user info is not allowed");
		}
		String host = uri.getHost();
		if (host == null || host.isEmpty()) {
			throw new RejectedDestinationException("a host is required");
		}
		host = normalize(host);
		if (!isAllowed(host)) {
			throw new RejectedDestinationException("host is not in the allowlist");
		}
		List<InetAddress> addresses = resolve(host);
		for (InetAddress address : addresses) {
			if (!NonPublicAddresses.isPublic(address)) {
				throw new RejectedDestinationException("host resolves to a non-public address");
			}
		}
		return new Destination(uri, host, addresses);
	}

	private boolean isAllowed(String host) {
		for (String entry : allowedHosts) {
			if (entry.startsWith(".") ? host.equals(entry.substring(1)) || host.endsWith(entry) : host.equals(entry)) {
				return true;
			}
		}
		return false;
	}

	private List<InetAddress> resolve(String host) {
		List<InetAddress> addresses = lookup(host);
		if (addresses == null || addresses.isEmpty()) {
			throw new DestinationResolutionException("host does not resolve");
		}
		return List.copyOf(addresses);
	}

	/**
	 * Runs the lookup on a virtual thread and waits at most the resolve timeout. A lookup that outlives the wait is
	 * interrupted, but a blocking system call may keep its thread until the operating system gives up.
	 */
	private List<InetAddress> lookup(String host) {
		FutureTask<List<InetAddress>> task = new FutureTask<>(() -> resolver.resolve(host));
		Thread.ofVirtual().name("destination-lookup").start(task);
		try {
			return task.get(resolveTimeout.toNanos(), TimeUnit.NANOSECONDS);
		}
		catch (TimeoutException ex) {
			task.cancel(true);
			throw new DestinationResolutionException("name resolution timed out");
		}
		catch (ExecutionException ex) {
			if (ex.getCause() instanceof UnknownHostException) {
				throw new DestinationResolutionException("host does not resolve");
			}
			if (ex.getCause() instanceof RuntimeException runtime) {
				throw runtime;
			}
			throw new IllegalStateException("Destination lookup failed", ex.getCause());
		}
		catch (InterruptedException ex) {
			task.cancel(true);
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while resolving the destination", ex);
		}
	}

	private static URI parse(String url) {
		if (url == null || url.isBlank()) {
			throw new RejectedDestinationException("a URL is required");
		}
		try {
			return new URI(url);
		}
		catch (URISyntaxException ex) {
			throw new RejectedDestinationException("the URL is malformed");
		}
	}

	/**
	 * Lowercase, without the trailing dot of a fully qualified name and without IPv6 brackets.
	 */
	private static String normalize(String host) {
		String normalized = host.toLowerCase(Locale.ROOT);
		if (normalized.startsWith("[") && normalized.endsWith("]")) {
			normalized = normalized.substring(1, normalized.length() - 1);
		}
		return normalized.endsWith(".") ? normalized.substring(0, normalized.length() - 1) : normalized;
	}

	/**
	 * A URL that passed the policy, with the addresses it was checked against.
	 *
	 * @param uri the parsed URL
	 * @param host the normalized host name
	 * @param addresses the public addresses the host resolved to; connect only to these
	 */
	public record Destination(URI uri, String host, List<InetAddress> addresses) {
	}

	/**
	 * Resolves a host name to all its addresses. Replaceable so tests never depend on real DNS.
	 */
	@FunctionalInterface
	public interface HostResolver {

		List<InetAddress> resolve(String host) throws UnknownHostException;

		/**
		 * The JVM resolver ({@link InetAddress#getAllByName(String)}): blocking, with the operating system's timeouts and
		 * the JVM's positive and negative caches.
		 */
		static HostResolver system() {
			return host -> List.of(InetAddress.getAllByName(host));
		}

	}

}
