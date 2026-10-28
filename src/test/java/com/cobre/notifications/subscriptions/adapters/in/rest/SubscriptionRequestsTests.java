package com.cobre.notifications.subscriptions.adapters.in.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatException;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The request DTOs reject unknown fields even with Spring Boot's lenient default of ignoring them.
 */
class SubscriptionRequestsTests {

	private final JsonMapper mapper = JsonMapper.builder()
		.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
		.build();

	@Test
	void createRequestReadsTheAcceptedFields() {
		CreateSubscriptionRequest request = mapper.readValue(
				"{\"url\":\"https://hooks.example.org/cobre\",\"event_types\":[\"credit_deposit\"]}",
				CreateSubscriptionRequest.class);

		assertThat(request.url()).isEqualTo("https://hooks.example.org/cobre");
		assertThat(request.eventTypes()).containsExactly("credit_deposit");
	}

	@Test
	void createRequestRejectsClientId() {
		assertThatException()
			.isThrownBy(() -> mapper.readValue(
					"{\"url\":\"https://hooks.example.org/cobre\",\"event_types\":[\"a\"],\"client_id\":\"CLIENT002\"}",
					CreateSubscriptionRequest.class))
			.withRootCauseInstanceOf(UnknownFieldException.class);
	}

	@Test
	void updateRequestReadsTheAcceptedFields() {
		UpdateSubscriptionRequest request = mapper.readValue("{\"active\":false}", UpdateSubscriptionRequest.class);

		assertThat(request.active()).isFalse();
		assertThat(request.eventTypes()).isNull();
	}

	@Test
	void updateRequestRejectsUrl() {
		assertThatException()
			.isThrownBy(() -> mapper.readValue("{\"url\":\"https://other.example.org/\"}",
					UpdateSubscriptionRequest.class))
			.withRootCauseInstanceOf(UnknownFieldException.class);
	}

}
