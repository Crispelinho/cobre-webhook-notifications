package com.cobre.notifications.shared.security;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class DestinationPolicyConfiguration {

	@Bean
	DestinationPolicy.HostResolver hostResolver() {
		return DestinationPolicy.HostResolver.system();
	}

	/**
	 * The allowlist comes from {@code app.webhook.allowed-hosts} (comma-separated), so a new host, such as the
	 * presentation one, needs a restart but no new build.
	 */
	@Bean
	DestinationPolicy destinationPolicy(@Value("${app.webhook.allowed-hosts:}") List<String> allowedHosts,
			DestinationPolicy.HostResolver hostResolver) {
		return new DestinationPolicy(allowedHosts, hostResolver);
	}

}
