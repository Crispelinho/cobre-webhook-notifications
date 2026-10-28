package com.cobre.notifications;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.grafana.LgtmStackContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Grafana LGTM stack for running the application locally ({@link TestNotificationServiceApplication}). Kept out of
 * {@link TestcontainersConfiguration} because every cached test context would start its own heavy LGTM container and
 * no test asserts against it.
 */
@TestConfiguration(proxyBeanMethods = false)
class LgtmContainerConfiguration {

	@Bean
	@ServiceConnection
	LgtmStackContainer grafanaLgtmContainer() {
		return new LgtmStackContainer(DockerImageName.parse("grafana/otel-lgtm:0.38.1"));
	}

}
