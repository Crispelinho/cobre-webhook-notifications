package com.cobre.notifications.notifications.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PageQueryTests {

	@Test
	void firstPageUsesTheDefaultSize() {
		assertThat(PageQuery.firstPage()).isEqualTo(new PageQuery(0, 20));
	}

	@ParameterizedTest
	@ValueSource(ints = { 0, 101 })
	void sizeOutsideOneToOneHundredIsRejected(int size) {
		assertThatIllegalArgumentException().isThrownBy(() -> new PageQuery(0, size));
	}

	@Test
	void negativePageIsRejected() {
		assertThatIllegalArgumentException().isThrownBy(() -> new PageQuery(-1, 20));
	}

	@Test
	void totalPagesRoundsUp() {
		assertThat(new Page<>(List.of(), 0, 20, 41).totalPages()).isEqualTo(3);
		assertThat(new Page<>(List.of(), 0, 20, 0).totalPages()).isZero();
	}

}
