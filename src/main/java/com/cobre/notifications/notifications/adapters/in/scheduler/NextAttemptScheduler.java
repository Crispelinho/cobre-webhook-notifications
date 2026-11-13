package com.cobre.notifications.notifications.adapters.in.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.cobre.notifications.notifications.application.EnqueueDueAttempts;

/**
 * Polls for attempts whose Retry-After time has come. Like the outbox relay, {@code fixedDelay} never overlaps polls.
 */
@Component
@ConditionalOnProperty(name = "app.delivery.next-attempt.enabled", havingValue = "true", matchIfMissing = true)
class NextAttemptScheduler {

	private static final Logger log = LoggerFactory.getLogger(NextAttemptScheduler.class);

	private final EnqueueDueAttempts enqueueDue;

	NextAttemptScheduler(EnqueueDueAttempts enqueueDue) {
		this.enqueueDue = enqueueDue;
	}

	@Scheduled(fixedDelayString = "${app.delivery.next-attempt.poll-interval:PT1S}",
			initialDelayString = "${app.delivery.next-attempt.poll-interval:PT1S}")
	void poll() {
		try {
			int enqueued = enqueueDue.enqueueDue();
			if (enqueued > 0) {
				log.debug("Enqueued {} attempts that were due", enqueued);
			}
		}
		catch (RuntimeException ex) {
			log.error("Next-attempt poll failed; will retry on next poll", ex);
		}
	}

}
