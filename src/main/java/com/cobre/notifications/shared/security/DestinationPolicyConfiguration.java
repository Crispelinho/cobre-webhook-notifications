package com.cobre.notifications.shared.security;

import java.time.Duration;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

@Configuration(proxyBeanMethods = false)
class DestinationPolicyConfiguration {

	private static final String ANY_HOST = "*";

	@Bean
	DestinationPolicy.HostResolver hostResolver() {
		return DestinationPolicy.HostResolver.system();
	}

	/**
	 * The allowlist comes from {@code app.webhook.allowed-hosts} (comma-separated), so a new host, such as the
	 * presentation one, needs a restart but no new build. The lookup pool size comes from
	 * {@code app.webhook.max-concurrent-lookups}, which defaults to {@link DestinationPolicy#MAX_CONCURRENT_LOOKUPS}.
	 * A {@code *} entry turns on the development mode: every host name passes the allowlist, and the address rule still
	 * rejects private, loopback and link-local addresses. The mode is refused outside the {@code local} profile.
	 */
	@Bean
	DestinationPolicy destinationPolicy(@Value("${app.webhook.allowed-hosts:}") List<String> allowedHosts,
			@Value("${app.webhook.max-concurrent-lookups:" + DestinationPolicy.MAX_CONCURRENT_LOOKUPS + "}")
			int maxConcurrentLookups, Environment environment, DestinationPolicy.HostResolver hostResolver) {
		boolean anyHost = allowedHosts.stream().map(String::strip).anyMatch(ANY_HOST::equals);
		if (anyHost && !environment.acceptsProfiles(Profiles.of("local"))) {
			throw new IllegalStateException("app.webhook.allowed-hosts may contain \"*\" only in the local profile");
		}
		List<String> hosts = allowedHosts.stream().map(String::strip).filter(host -> !ANY_HOST.equals(host)).toList();
		return new DestinationPolicy(hosts, hostResolver, DestinationPolicy.DEFAULT_RESOLVE_TIMEOUT, maxConcurrentLookups,
				anyHost);
	}

	static DestinationPolicy destinationPolicy(List<String> allowedHosts, Environment environment,
			DestinationPolicy.HostResolver hostResolver, Duration resolveTimeout) {
		boolean anyHost = allowedHosts.stream().map(String::strip).anyMatch(ANY_HOST::equals);
		if (anyHost && !environment.acceptsProfiles(Profiles.of("local"))) {
			throw new IllegalStateException("app.webhook.allowed-hosts may contain \"*\" only in the local profile");
		}
		List<String> hosts = allowedHosts.stream().map(String::strip).filter(host -> !ANY_HOST.equals(host)).toList();
		return new DestinationPolicy(hosts, hostResolver, resolveTimeout, DestinationPolicy.MAX_CONCURRENT_LOOKUPS, anyHost);
	}

}
