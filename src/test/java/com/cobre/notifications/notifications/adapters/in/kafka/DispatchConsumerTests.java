package com.cobre.notifications.notifications.adapters.in.kafka;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.cobre.notifications.notifications.application.DeliveryResult;

import tools.jackson.databind.json.JsonMapper;

/**
 * How the consumer turns a delivery result into what Kafka sees, without a broker.
 */
class DispatchConsumerTests {

	private static final String PAYLOAD = "{\"notification_event_id\": \"evt-1\", \"round\": 2}";

	private DispatchConsumer consumerReturning(DeliveryResult result) {
		return new DispatchConsumer(message -> result, new DispatchPayloadParser(JsonMapper.builder().build()));
	}

	@Test
	void exhaustedThrowsTheExceptionThatSkipsTheRetryTopics() {
		assertThatExceptionOfType(ExhaustedDeliveryException.class)
			.isThrownBy(() -> consumerReturning(DeliveryResult.EXHAUSTED).onMessage(PAYLOAD));
	}

	@Test
	void retryThrowsTheRetryableException() {
		assertThatExceptionOfType(RetryableDeliveryException.class)
			.isThrownBy(() -> consumerReturning(DeliveryResult.RETRY).onMessage(PAYLOAD));
	}

	@ParameterizedTest
	@EnumSource(value = DeliveryResult.class, names = { "DELIVERED", "FAILED", "SCHEDULED", "IGNORED" })
	void everyOtherResultThrowsNothing(DeliveryResult result) {
		assertThatCode(() -> consumerReturning(result).onMessage(PAYLOAD)).doesNotThrowAnyException();
	}

}
