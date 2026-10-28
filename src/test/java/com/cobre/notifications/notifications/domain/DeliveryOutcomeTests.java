package com.cobre.notifications.notifications.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class DeliveryOutcomeTests {

	@ParameterizedTest
	@ValueSource(ints = { 200, 201, 202, 204, 299 })
	void every2xxIsASuccess(int status) {
		assertThat(DeliveryOutcome.classify(status)).isEqualTo(DeliveryOutcome.SUCCESS);
	}

	@ParameterizedTest
	@ValueSource(ints = { 500, 502, 503, 504, 599, 408, 429 })
	void serverErrorsRequestTimeoutAndTooManyRequestsAreRetryable(int status) {
		assertThat(DeliveryOutcome.classify(status)).isEqualTo(DeliveryOutcome.RETRYABLE);
	}

	@ParameterizedTest
	@ValueSource(ints = { 400, 401, 403, 404, 410, 422 })
	void otherClientErrorsArePermanent(int status) {
		assertThat(DeliveryOutcome.classify(status)).isEqualTo(DeliveryOutcome.PERMANENT);
	}

	@ParameterizedTest
	@ValueSource(ints = { 300, 301, 302, 303, 307, 308 })
	void aRedirectIsAPermanentFailure(int status) {
		assertThat(DeliveryOutcome.classify(status)).isEqualTo(DeliveryOutcome.PERMANENT);
	}

	@ParameterizedTest
	@ValueSource(ints = { 100, 199, 600, 0, -1 })
	void statusesOutsideTheUsableRangeArePermanent(int status) {
		assertThat(DeliveryOutcome.classify(status)).isEqualTo(DeliveryOutcome.PERMANENT);
	}

	@ParameterizedTest
	@CsvSource({ "TIMEOUT,RETRYABLE", "CONNECTION,RETRYABLE", "TLS,PERMANENT", "INVALID_DESTINATION,PERMANENT" })
	void transportErrorsAreClassified(TransportError error, DeliveryOutcome expected) {
		assertThat(DeliveryOutcome.classify(error)).isEqualTo(expected);
	}

	@ParameterizedTest
	@EnumSource(TransportError.class)
	void transportErrorCodesAreShortAndFreeOfContent(TransportError error) {
		assertThat(error.errorType()).matches("[a-z_]{1,64}");
	}

}
