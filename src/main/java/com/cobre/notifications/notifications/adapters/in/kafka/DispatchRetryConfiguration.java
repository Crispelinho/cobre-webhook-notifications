package com.cobre.notifications.notifications.adapters.in.kafka;

import java.time.Duration;
import java.util.Iterator;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.retrytopic.RetryTopicConfiguration;
import org.springframework.kafka.retrytopic.RetryTopicConfigurationBuilder;
import org.springframework.kafka.retrytopic.SameIntervalTopicReuseStrategy;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.BackOffExecution;

/**
 * Non-blocking retries for {@value DispatchConsumer#TOPIC}: one retry topic per backoff step
 * ({@code notification.dispatch-retry-0}, {@code -1}, ...) and the dead-letter topic
 * {@code notification.dispatch-dlt}. This is the programmatic form of {@code @RetryableTopic}; it is used because the
 * demo schedule (10 s, 30 s, 1 min, 2 min) is not exponential and the schedule must change per profile.
 *
 * <p>
 * Schedule: if {@code app.delivery.retry.delays} is set (the {@code demo} profile does), those delays are used;
 * otherwise the default exponential backoff. A retry topic has one fixed delay, so the jitter of the exponential
 * schedule is drawn once per topic when the application starts, not per message.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.delivery.consumer.enabled", havingValue = "true", matchIfMissing = true)
class DispatchRetryConfiguration {

	@Bean
	RetryTopicConfiguration dispatchRetryTopicConfiguration(KafkaTemplate<String, String> kafka,
			@Value("${app.delivery.retry.max-attempts:5}") int maxAttempts,
			@Value("${app.delivery.retry.delays:}") List<Duration> delays,
			@Value("${app.delivery.retry.initial-delay:PT30S}") Duration initialDelay,
			@Value("${app.delivery.retry.multiplier:4}") double multiplier,
			@Value("${app.delivery.retry.max-delay:PT1H}") Duration maxDelay,
			@Value("${app.delivery.retry.jitter:PT5S}") Duration jitter,
			@Value("${app.dispatch.partitions:3}") int partitions) {
		RetryTopicConfigurationBuilder builder = RetryTopicConfigurationBuilder.newInstance()
			.includeTopic(DispatchConsumer.TOPIC)
			.maxAttempts(maxAttempts)
			.suffixTopicsWithIndexValues()
			// Same partition count as the dispatch topic; -1 keeps the broker default replication factor.
			.autoCreateTopicsWith(partitions, (short) -1)
			.sameIntervalTopicReuseStrategy(SameIntervalTopicReuseStrategy.MULTIPLE_TOPICS)
			.dltHandlerMethod("dispatchDeadLetterHandler", "onDeadLetter")
			// Not retried, and still dead-lettered: the dead-letter alert and metric depend on it. A malformed message has no
			// event to retry; an exhausted one already failed its event in the same write as its last attempt.
			.notRetryOn(List.of(MalformedDispatchMessageException.class, ExhaustedDeliveryException.class))
			.traversingCauses();
		if (delays.isEmpty()) {
			builder.exponentialBackoff(initialDelay.toMillis(), multiplier, maxDelay.toMillis(), jitter.toMillis());
		}
		else {
			if (delays.size() != maxAttempts - 1) {
				throw new IllegalStateException(
						"app.delivery.retry.delays needs %d entries (max-attempts - 1) but has %d"
							.formatted(maxAttempts - 1, delays.size()));
			}
			builder.customBackoff(fixedSchedule(delays));
		}
		return builder.create(kafka);
	}

	private static BackOff fixedSchedule(List<Duration> delays) {
		return () -> {
			Iterator<Duration> next = delays.iterator();
			return () -> next.hasNext() ? next.next().toMillis() : BackOffExecution.STOP;
		};
	}

}
