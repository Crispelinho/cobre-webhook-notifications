package com.cobre.notifications;

import java.security.SecureRandom;
import java.util.Base64;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.grafana.LgtmStackContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

	@Bean
	@ServiceConnection
	LgtmStackContainer grafanaLgtmContainer() {
		return new LgtmStackContainer(DockerImageName.parse("grafana/otel-lgtm:0.38.1"));
	}

	@Bean
	@ServiceConnection
	KafkaContainer kafkaContainer() {
		return new KafkaContainer(DockerImageName.parse("apache/kafka-native:4.4.0"));
	}

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer(DockerImageName.parse("postgres:18.6"));
	}

	/**
	 * Makes the application trust {@link TestJwtIssuer#trusted()} tokens.
	 */
	@Bean
	DynamicPropertyRegistrar jwtProperties() {
		return registry -> {
			registry.add("app.security.jwt.public-key-location", TestJwtIssuer::trustedPublicKeyLocation);
			registry.add("app.security.jwt.issuer", () -> TestJwtIssuer.ISSUER);
			registry.add("app.security.jwt.audience", () -> TestJwtIssuer.AUDIENCE);
		};
	}

	/**
	 * A fresh webhook secrets key per test run, so no key is ever committed for tests.
	 */
	@Bean
	DynamicPropertyRegistrar webhookSecretsKey() {
		byte[] bytes = new byte[32];
		new SecureRandom().nextBytes(bytes);
		String key = Base64.getEncoder().encodeToString(bytes);
		return registry -> registry.add("app.webhook.secrets-key", () -> key);
	}

}
