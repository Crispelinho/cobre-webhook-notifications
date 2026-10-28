package com.cobre.notifications.shared.security;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

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
 * The check resolves DNS on the calling thread and blocks until the resolver answers; delivery code should call it
 * from a bounded worker, not from a request thread with no timeout.
 */
public final class DestinationPolicy {

	private static final int HTTPS_PORT = 443;

	private final List<String> allowedHosts;

	private final HostResolver resolver;

	public DestinationPolicy(Collection<String> allowedHosts, HostResolver resolver) {
		this.allowedHosts = allowedHosts.stream()
			.map(String::strip)
			.filter(host -> !host.isEmpty())
			.map(DestinationPolicy::normalize)
			.toList();
		this.resolver = Objects.requireNonNull(resolver);
	}

	/**
	 * Returns the validated destination, or throws {@link RejectedDestinationException} with a reason meant for logs.
	 * The reason should not be shown to API callers: it would reveal how internal names resolve.
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
		try {
			List<InetAddress> addresses = resolver.resolve(host);
			if (addresses == null || addresses.isEmpty()) {
				throw new RejectedDestinationException("host does not resolve");
			}
			return List.copyOf(addresses);
		}
		catch (UnknownHostException ex) {
			throw new RejectedDestinationException("host does not resolve");
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
