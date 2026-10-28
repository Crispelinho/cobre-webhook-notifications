package com.cobre.notifications.notifications.adapters.in.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.cobre.notifications.notifications.application.RelayOutbox;

/**
 * Polls the outbox. {@code fixedDelay} waits for the previous poll to end, so a slow or unavailable broker never
 * causes overlapping polls or a busy loop.
 */
@Component
@ConditionalOnProperty(name = "app.outbox.relay.enabled", havingValue = "true", matchIfMissing = true)
class OutboxRelayScheduler {

	private static final Logger log = LoggerFactory.getLogger(OutboxRelayScheduler.class);

	private final RelayOutbox relay;

	OutboxRelayScheduler(RelayOutbox relay) {
		this.relay = relay;
	}

	@Scheduled(fixedDelayString = "${app.outbox.relay.poll-interval:PT0.5S}",
			initialDelayString = "${app.outbox.relay.poll-interval:PT0.5S}")
	void poll() {
		try {
			int published = relay.relayPending();
			if (published > 0) {
				log.debug("Relayed {} dispatch messages", published);
			}
		}
		catch (RuntimeException ex) {
			log.error("Outbox relay poll failed; will retry on next poll", ex);
		}
	}

}
