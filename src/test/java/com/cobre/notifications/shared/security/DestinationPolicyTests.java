package com.cobre.notifications.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.cobre.notifications.shared.security.DestinationPolicy.Destination;
import com.cobre.notifications.shared.security.DestinationPolicy.HostResolver;

/**
 * Unit tests of the SSRF rules. A fake resolver stands in for DNS, so a host name can be made to resolve to any
 * address.
 */
class DestinationPolicyTests {

	private final FakeResolver resolver = new FakeResolver(Map.of("hooks.example.org", "93.184.215.14",
			"api.partner.example.net", "93.184.215.15", "partner.example.net", "93.184.215.16"));

	private final DestinationPolicy policy = new DestinationPolicy(List.of("hooks.example.org", " .partner.example.net "),
			resolver);

	@Test
	void httpsUrlOnAnAllowlistedPublicHostIsAccepted() {
		Destination destination = policy.check("https://hooks.example.org/cobre");

		assertThat(destination.uri().getPath()).isEqualTo("/cobre");
		assertThat(destination.host()).isEqualTo("hooks.example.org");
		assertThat(destination.addresses()).extracting(InetAddress::getHostAddress).containsExactly("93.184.215.14");
	}

	@Test
	void plainHttpIsRejected() {
		expectRejected("http://hooks.example.org/cobre", "only https is allowed");
	}

	@Test
	void hostOutsideTheAllowlistIsRejected() {
		expectRejected("https://not-allowed.example.net/", "host is not in the allowlist");
	}

	@ParameterizedTest
	@ValueSource(strings = { "127.0.0.1", "10.0.0.5", "169.254.169.254", "172.16.0.1", "192.168.1.10", "100.64.0.1",
			"0.0.0.0", "224.0.0.1", "255.255.255.255", "::1", "::", "fe80::1", "fd00:ec2::254", "fc00::1", "ff02::1",
			"64:ff9b::a00:5", "2002:a00:5::1" })
	void allowlistedHostResolvingToANonPublicAddressIsRejected(String address) {
		DestinationPolicy policy = new DestinationPolicy(List.of("internal.example.org"),
				new FakeResolver(Map.of("internal.example.org", address)));

		assertThatExceptionOfType(RejectedDestinationException.class)
			.isThrownBy(() -> policy.check("https://internal.example.org/"))
			.withMessage("host resolves to a non-public address");
	}

	@Test
	void oneNonPublicAddressAmongSeveralIsEnoughToReject() throws UnknownHostException {
		DestinationPolicy policy = new DestinationPolicy(List.of("mixed.example.org"),
				host -> List.of(InetAddress.getByName("93.184.215.14"), InetAddress.getByName("10.0.0.5")));

		expectRejected(policy, "https://mixed.example.org/", "host resolves to a non-public address");
	}

	@Test
	void ipv4MappedIpv6AddressIsRejected() throws UnknownHostException {
		byte[] mappedLoopback = { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, (byte) 0xFF, (byte) 0xFF, 127, 0, 0, 1 };
		InetAddress address = Inet6Address.getByAddress("mapped.example.org", mappedLoopback, -1);
		DestinationPolicy policy = new DestinationPolicy(List.of("mapped.example.org"), host -> List.of(address));

		expectRejected(policy, "https://mapped.example.org/", "host resolves to a non-public address");
	}

	@Test
	void presentationHostFromConfigurationIsAccepted() {
		// Same code path as WEBHOOK_ALLOWED_HOSTS: the allowlist is plain data given at startup.
		DestinationPolicy policy = new DestinationPolicy(List.of("demo-receiver.example.com"),
				new FakeResolver(Map.of("demo-receiver.example.com", "93.184.215.20")));

		assertThat(policy.check("https://demo-receiver.example.com/webhooks").host())
			.isEqualTo("demo-receiver.example.com");
	}

	@Test
	void dotEntryAllowsTheDomainAndItsSubdomains() {
		assertThat(policy.check("https://api.partner.example.net/hook").host()).isEqualTo("api.partner.example.net");
		assertThat(policy.check("https://partner.example.net/hook").host()).isEqualTo("partner.example.net");
	}

	@Test
	void dotEntryDoesNotAllowLookalikeDomains() {
		expectRejected("https://evilpartner.example.net/", "host is not in the allowlist");
	}

	@Test
	void exactEntryDoesNotAllowSubdomains() {
		expectRejected("https://sub.hooks.example.org/", "host is not in the allowlist");
	}

	@Test
	void hostMatchingIgnoresCaseAndTheTrailingDot() {
		assertThat(policy.check("HTTPS://Hooks.Example.ORG./cobre").host()).isEqualTo("hooks.example.org");
	}

	@Test
	void userInfoIsRejected() {
		expectRejected("https://user:pass@hooks.example.org/", "user info is not allowed");
	}

	@ParameterizedTest
	@ValueSource(strings = { "", " ", "not a url", "https:///path", "https://hooks example.org/", "mailto:a@b.c" })
	void malformedUrlsAreRejected(String url) {
		assertThatExceptionOfType(RejectedDestinationException.class).isThrownBy(() -> policy.check(url));
	}

	@Test
	void nullUrlIsRejected() {
		expectRejected(null, "a URL is required");
	}

	@Test
	void hostThatDoesNotResolveIsRejected() {
		DestinationPolicy policy = new DestinationPolicy(List.of("gone.example.org"), resolver);

		expectRejected(policy, "https://gone.example.org/", "host does not resolve");
	}

	@Test
	void emptyAllowlistRejectsEverything() {
		DestinationPolicy policy = new DestinationPolicy(List.of(), resolver);

		expectRejected(policy, "https://hooks.example.org/cobre", "host is not in the allowlist");
	}

	@Test
	void rejectionMessageNeverContainsTheUrl() {
		assertThatExceptionOfType(RejectedDestinationException.class)
			.isThrownBy(() -> policy.check("https://not-allowed.example.net/secret-path"))
			.withMessageNotContaining("not-allowed")
			.withMessageNotContaining("secret-path");
	}

	private void expectRejected(String url, String reason) {
		expectRejected(policy, url, reason);
	}

	private static void expectRejected(DestinationPolicy policy, String url, String reason) {
		assertThatExceptionOfType(RejectedDestinationException.class).isThrownBy(() -> policy.check(url))
			.withMessage(reason);
	}

	/**
	 * Resolves names from a fixed map of literal addresses; unknown names fail like an NXDOMAIN answer.
	 */
	private record FakeResolver(Map<String, String> addresses) implements HostResolver {

		@Override
		public List<InetAddress> resolve(String host) throws UnknownHostException {
			String address = addresses.get(host);
			if (address == null) {
				throw new UnknownHostException(host);
			}
			return List.of(InetAddress.getByName(address));
		}

	}

}
