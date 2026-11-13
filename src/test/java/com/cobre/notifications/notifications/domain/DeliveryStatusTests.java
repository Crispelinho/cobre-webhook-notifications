package com.cobre.notifications.notifications.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class DeliveryStatusTests {

	@ParameterizedTest
	@EnumSource(DeliveryStatus.class)
	void wireValueRoundTrips(DeliveryStatus status) {
		assertThat(DeliveryStatus.fromWireValue(status.wireValue())).isEqualTo(status);
	}

	@ParameterizedTest
	@ValueSource(strings = { "delivered", "FAILED", "" })
	void unknownOrNonCanonicalValuesAreRejected(String value) {
		assertThatIllegalArgumentException().isThrownBy(() -> DeliveryStatus.fromWireValue(value));
	}

}
