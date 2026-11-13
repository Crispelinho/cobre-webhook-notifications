package com.cobre.notifications.notifications.adapters.out.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import com.cobre.notifications.notifications.domain.AttemptResult;
import com.cobre.notifications.notifications.domain.Delivery;
import com.cobre.notifications.notifications.domain.DeliveryOutcome;
import com.cobre.notifications.notifications.domain.DeliveryStatus;
import com.cobre.notifications.notifications.domain.NotificationEventId;
import com.cobre.notifications.notifications.domain.TransportError;
import com.cobre.notifications.shared.crypto.SecretCipher;
import com.cobre.notifications.shared.crypto.WebhookSigner;
import com.cobre.notifications.shared.security.DestinationPolicy;
import com.cobre.notifications.support.WebhookTestServer;

import tools.jackson.databind.json.JsonMapper;

/**
 * The seed data points at {@code webhook.example.com}. That host is allowed in the {@code local} profile only: these
 * tests read the real {@code application.yaml} and {@code application-local.yaml}, build the destination policy from
 * the resulting allowlist and run the production sender, so the default (production) configuration is proven to
 * reject the seed host without sending anything.
 */
class WebhookAllowlistConfigurationTests {

	private static final String SEED_URL = "https://webhook.example.com/CLIENT001";

	private final List<InetSocketAddress> connections = new ArrayList<>();

	@Test
	void theDefaultConfigurationRejectsTheSeedHostAndSendsNothing() throws IOException {
		MockEnvironment production = environment(false);

		AttemptResult result = send(production, SEED_URL);

		assertThat(result.error()).isEqualTo(TransportError.INVALID_DESTINATION);
		assertThat(result.outcome()).isEqualTo(DeliveryOutcome.PERMANENT);
		assertThat(connections).isEmpty();
	}

	@Test
	void theDefaultAllowlistIsEmptyUnlessOperatorsSetIt() {
		assertThat(environment(false).getProperty("app.webhook.allowed-hosts", "")).isEmpty();
	}

	@Test
	void theLocalProfileAllowsTheSeedHost() {
		MockEnvironment local = environment(true);

		assertThat(local.getProperty("app.webhook.allowed-hosts")).isEqualTo("webhook.example.com");
		assertThatCode(() -> policy(local).check(SEED_URL)).doesNotThrowAnyException();
	}

	@Test
	void theLocalProfileStillRejectsEveryOtherHost() throws IOException {
		MockEnvironment local = environment(true);

		AttemptResult result = send(local, "https://evil.example.net/hook");

		assertThat(result.error()).isEqualTo(TransportError.INVALID_DESTINATION);
		assertThat(connections).isEmpty();
	}

	private AttemptResult send(MockEnvironment environment, String url) throws IOException {
		try (CloseableHttpClient client = WebhookTestServer.redirectingHttpClient(
				WebhookTestServer.trustingSslContext(), () -> 1, Duration.ofSeconds(1), connections::add,
				address -> false)) {
			SecretCipher cipher = SecretCipher
				.fromBase64Key(Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes()));
			HttpsWebhookSender sender = new HttpsWebhookSender(client, JsonMapper.builder().build(), policy(environment),
					cipher, new WebhookSigner(), Duration.ofSeconds(1), Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
			return sender.send(new Delivery(new NotificationEventId("N1"), "E1", "credit_deposit", "content", url,
					Instant.EPOCH, DeliveryStatus.PENDING, 1, 0, null, null, null, 0));
		}
	}

	/**
	 * The policy exactly as {@code DestinationPolicyConfiguration} builds it: allowlist from
	 * {@code app.webhook.allowed-hosts} (comma-separated). DNS is faked so every name resolves to a public address:
	 * only the allowlist decides.
	 */
	private static DestinationPolicy policy(MockEnvironment environment) {
		String configured = environment.getProperty("app.webhook.allowed-hosts", "");
		return new DestinationPolicy(List.of(configured.split(",")),
				host -> List.of(InetAddress.getByName("93.184.215.14")));
	}

	/**
	 * The real configuration files, in Spring Boot's order (profile file over the base file), without OS environment
	 * variables so a developer machine cannot change the result.
	 */
	private static MockEnvironment environment(boolean localProfile) {
		MockEnvironment environment = new MockEnvironment();
		try {
			YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
			if (localProfile) {
				for (PropertySource<?> source : loader.load("local", new ClassPathResource("application-local.yaml"))) {
					environment.getPropertySources().addLast(source);
				}
			}
			for (PropertySource<?> source : loader.load("base", new ClassPathResource("application.yaml"))) {
				environment.getPropertySources().addLast(source);
			}
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
		return environment;
	}

}
