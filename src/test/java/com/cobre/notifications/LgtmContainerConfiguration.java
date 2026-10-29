package com.cobre.notifications;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.grafana.LgtmStackContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Grafana LGTM stack for running the application locally ({@link TestNotificationServiceApplication}). Kept out of
 * {@link TestcontainersConfiguration} because every cached test context would start its own heavy LGTM container and
 * no test asserts against it. It gets the same dashboard and alert rules that {@code compose.yaml} provisions.
 */
@TestConfiguration(proxyBeanMethods = false)
class LgtmContainerConfiguration {

	private static final String PROVISIONING = "/otel-lgtm/grafana/conf/provisioning";

	@Bean
	@ServiceConnection
	LgtmStackContainer grafanaLgtmContainer() {
		return new LgtmStackContainer(DockerImageName.parse("grafana/otel-lgtm:0.38.1"))
			.withCopyFileToContainer(
					MountableFile.forHostPath("docker/grafana/provisioning/dashboards/notification-delivery.yaml"),
					PROVISIONING + "/dashboards/notification-delivery.yaml")
			.withCopyFileToContainer(
					MountableFile.forHostPath("docker/grafana/provisioning/alerting/notification-delivery-alerts.yaml"),
					PROVISIONING + "/alerting/notification-delivery-alerts.yaml")
			.withCopyFileToContainer(MountableFile.forHostPath("docker/grafana/dashboards"),
					"/var/lib/cobre/dashboards");
	}

}
