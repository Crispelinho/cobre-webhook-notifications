package com.cobre.notifications;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistrar;

import com.cobre.notifications.shared.security.DestinationPolicy.HostResolver;

/**
 * Tests never query real DNS: the destination policy resolves names from this fixed map, and any other name fails
 * like an NXDOMAIN answer. The allowlist contains every name below, so each one reaches the address check.
 */
@TestConfiguration(proxyBeanMethods = false)
class FakeDnsConfiguration {

	private static final Map<String, String> ADDRESSES = Map.of("hooks.example.org", "93.184.215.14",
			"presentation.example.com", "93.184.215.20", "loopback.example.org", "127.0.0.1",
			"private.example.org", "10.0.0.5", "metadata.example.org", "169.254.169.254");

	@Bean
	@Primary
	HostResolver fakeHostResolver() {
		return host -> {
			String address = ADDRESSES.get(host);
			if (address == null) {
				throw new UnknownHostException(host);
			}
			return List.of(InetAddress.getByName(address));
		};
	}

	@Bean
	DynamicPropertyRegistrar webhookAllowedHosts() {
		return registry -> registry.add("app.webhook.allowed-hosts", () -> String.join(",", ADDRESSES.keySet()));
	}

}
