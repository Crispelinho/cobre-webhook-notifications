package com.cobre.notifications.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

class DestinationPolicyAnyHostTests {

	private static DestinationPolicy.HostResolver resolving(String address) {
		return host -> List.of(InetAddress.getByName(address));
	}

	@Test
	void anyHostResolvingToAPublicAddressIsAccepted() {
		DestinationPolicy policy = new DestinationPolicy(List.of(), resolving("93.184.215.14"),
				DestinationPolicy.DEFAULT_RESOLVE_TIMEOUT, DestinationPolicy.MAX_CONCURRENT_LOOKUPS, true);

		assertThat(policy.check("https://any-tunnel-name.example.net/cobre").host()).isEqualTo("any-tunnel-name.example.net");
	}

	@ParameterizedTest
	@ValueSource(strings = { "10.0.0.5", "192.168.1.1", "127.0.0.1", "169.254.169.254" })
	void anyHostResolvingToAPrivateLoopbackOrLinkLocalAddressIsStillRejected(String address) {
		DestinationPolicy policy = new DestinationPolicy(List.of(), resolving(address),
				DestinationPolicy.DEFAULT_RESOLVE_TIMEOUT, DestinationPolicy.MAX_CONCURRENT_LOOKUPS, true);

		assertThatExceptionOfType(RejectedDestinationException.class)
			.isThrownBy(() -> policy.check("https://any-tunnel-name.example.net/cobre"))
			.withMessage("host resolves to a non-public address");
	}

	@Test
	void anyHostThatDoesNotResolveIsStillATransientResolutionFailure() {
		DestinationPolicy.HostResolver none = host -> {
			throw new UnknownHostException(host);
		};
		DestinationPolicy policy = new DestinationPolicy(List.of(), none, DestinationPolicy.DEFAULT_RESOLVE_TIMEOUT, DestinationPolicy.MAX_CONCURRENT_LOOKUPS, true);

		assertThatExceptionOfType(DestinationResolutionException.class)
			.isThrownBy(() -> policy.check("https://missing.example.net/cobre"));
	}

	@Test
	void withoutAWildcardAnUnlistedHostIsRejected() {
		DestinationPolicy policy = new DestinationPolicy(List.of("hooks.example.org"), resolving("93.184.215.14"));

		assertThatExceptionOfType(RejectedDestinationException.class)
			.isThrownBy(() -> policy.check("https://any-tunnel-name.example.net/cobre"))
			.withMessage("host is not in the allowlist");
	}

	@Test
	void aWildcardInTheAllowlistIsRefusedOutsideTheLocalProfile() {
		MockEnvironment production = new MockEnvironment();
		production.setActiveProfiles("prod");

		assertThatIllegalStateException()
			.isThrownBy(() -> DestinationPolicyConfiguration.destinationPolicy(List.of("*"), production,
					resolving("93.184.215.14"), Duration.ofSeconds(5)))
			.withMessageContaining("only in the local profile");
	}

	@Test
	void aWildcardInTheAllowlistIsAcceptedInTheLocalProfile() {
		MockEnvironment local = new MockEnvironment();
		local.setActiveProfiles("local");

		DestinationPolicy policy = DestinationPolicyConfiguration.destinationPolicy(List.of("*"), local,
				resolving("93.184.215.14"), Duration.ofSeconds(5));

		assertThat(policy.check("https://any-tunnel-name.example.net/cobre").host()).isEqualTo("any-tunnel-name.example.net");
	}
}
