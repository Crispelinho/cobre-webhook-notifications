package com.cobre.notifications.notifications.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.cobre.notifications.notifications.application.port.out.DispatchPublisher;
import com.cobre.notifications.notifications.application.port.out.OutboxPort;

class RelayOutboxTests {

	private final FakeOutbox outbox = new FakeOutbox(new DispatchMessage("N1", 1), new DispatchMessage("N2", 1),
			new DispatchMessage("N3", 2));

	private final List<DispatchMessage> sent = new ArrayList<>();

	@Test
	void publishesEveryPendingRowInOrderAndMarksThemPublished() {
		RelayOutbox relay = new RelayOutbox(outbox, sent::add, 10);

		assertThat(relay.relayPending()).isEqualTo(3);

		assertThat(sent).extracting(DispatchMessage::notificationEventId).containsExactly("N1", "N2", "N3");
		assertThat(outbox.pending).isEmpty();
	}

	@Test
	void passesTheConfiguredBatchSizeToTheOutbox() {
		RelayOutbox relay = new RelayOutbox(outbox, sent::add, 2);

		assertThat(relay.relayPending()).isEqualTo(2);

		assertThat(outbox.pending).extracting(DispatchMessage::notificationEventId).containsExactly("N3");
	}

	@Test
	void stopsAtTheFirstFailureAndLeavesTheRestUnpublished() {
		DispatchPublisher failsOnSecond = message -> {
			if (message.notificationEventId().equals("N2")) {
				throw new DispatchPublishException("broker down", new RuntimeException());
			}
			sent.add(message);
		};
		RelayOutbox relay = new RelayOutbox(outbox, failsOnSecond, 10);

		assertThat(relay.relayPending()).isEqualTo(1);

		assertThat(sent).extracting(DispatchMessage::notificationEventId).containsExactly("N1");
		assertThat(outbox.pending).extracting(DispatchMessage::notificationEventId).containsExactly("N2", "N3");
	}

	@Test
	void publishesPendingRowsOnALaterPollOnceThePublisherRecovers() {
		boolean[] brokerUp = { false };
		DispatchPublisher flaky = message -> {
			if (!brokerUp[0]) {
				throw new DispatchPublishException("broker down", new RuntimeException());
			}
			sent.add(message);
		};
		RelayOutbox relay = new RelayOutbox(outbox, flaky, 10);

		assertThat(relay.relayPending()).isZero();
		brokerUp[0] = true;
		assertThat(relay.relayPending()).isEqualTo(3);

		assertThat(outbox.pending).isEmpty();
	}

	@Test
	void rejectsANonPositiveBatchSize() {
		assertThatThrownBy(() -> new RelayOutbox(outbox, sent::add, 0)).isInstanceOf(IllegalArgumentException.class);
	}

	private static final class FakeOutbox implements OutboxPort {

		private final List<DispatchMessage> pending = new ArrayList<>();

		FakeOutbox(DispatchMessage... messages) {
			pending.addAll(List.of(messages));
		}

		@Override
		public int relayBatch(int limit, Handler handler) {
			int published = 0;
			for (DispatchMessage message : List.copyOf(pending).subList(0, Math.min(limit, pending.size()))) {
				if (!handler.handle(message)) {
					break;
				}
				pending.remove(message);
				published++;
			}
			return published;
		}

	}

}
