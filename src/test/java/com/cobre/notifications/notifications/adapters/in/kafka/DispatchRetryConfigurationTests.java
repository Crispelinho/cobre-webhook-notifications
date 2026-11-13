package com.cobre.notifications.notifications.adapters.in.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.retrytopic.DestinationTopic;
import org.springframework.kafka.retrytopic.RetryTopicConfiguration;

/**
 * The per-profile schedules, without a broker: the retry topics and their delays come out as configured.
 */
class DispatchRetryConfigurationTests {

	private final KafkaTemplate<String, String> kafka = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(Map.of()));

	private final DispatchRetryConfiguration configuration = new DispatchRetryConfiguration();

	@Test
	void demoScheduleRetriesAfter10s30s1minAnd2minThenGoesToTheDeadLetterTopic() {
		RetryTopicConfiguration retry = configuration.dispatchRetryTopicConfiguration(kafka, 5,
				List.of(Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofMinutes(1), Duration.ofMinutes(2)),
				Duration.ofSeconds(30), 4, Duration.ofHours(1), Duration.ofSeconds(5), 3);

		assertThat(retry.getDestinationTopicProperties()).filteredOn(DestinationTopic.Properties::isRetryTopic)
			.extracting(DestinationTopic.Properties::suffix, DestinationTopic.Properties::delay)
			.containsExactly(org.assertj.core.groups.Tuple.tuple("-retry-0", 10_000L),
					org.assertj.core.groups.Tuple.tuple("-retry-1", 30_000L),
					org.assertj.core.groups.Tuple.tuple("-retry-2", 60_000L),
					org.assertj.core.groups.Tuple.tuple("-retry-3", 120_000L));
		assertThat(retry.getDestinationTopicProperties()).filteredOn(DestinationTopic.Properties::isDltTopic)
			.extracting(DestinationTopic.Properties::suffix)
			.containsExactly("-dlt");
	}

	@Test
	void defaultScheduleIsExponentialFrom30sCappedAtOneHourWithJitter() {
		RetryTopicConfiguration retry = configuration.dispatchRetryTopicConfiguration(kafka, 5, List.of(),
				Duration.ofSeconds(30), 4, Duration.ofHours(1), Duration.ofSeconds(5), 3);

		List<Long> delays = retry.getDestinationTopicProperties()
			.stream()
			.filter(DestinationTopic.Properties::isRetryTopic)
			.map(DestinationTopic.Properties::delay)
			.toList();

		assertThat(delays).hasSize(4);
		assertThat(delays.get(0)).isBetween(25_000L, 35_000L);
		assertThat(delays).isSorted().allSatisfy(delay -> assertThat(delay).isLessThanOrEqualTo(3_605_000L));
		assertThat(delays.get(3)).isGreaterThan(delays.get(0));
	}

	@Test
	void anExplicitScheduleMustMatchTheAttemptBudget() {
		assertThatIllegalStateException().isThrownBy(() -> configuration.dispatchRetryTopicConfiguration(kafka, 5,
				List.of(Duration.ofSeconds(10)), Duration.ofSeconds(30), 4, Duration.ofHours(1), Duration.ofSeconds(5),
				3));
	}

}
