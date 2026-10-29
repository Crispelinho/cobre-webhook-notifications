package com.cobre.notifications.notifications.adapters.in.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.cobre.notifications.notifications.application.RecoverInterruptedDeliveries;

/**
 * Sweeps expired leases. {@code fixedDelay} waits for the previous sweep to end, so sweeps never overlap in one
 * instance; several instances can sweep at once because the store skips rows another instance is recovering.
 */
@Component
@ConditionalOnProperty(name = "app.delivery.lease.recovery.enabled", havingValue = "true", matchIfMissing = true)
class LeaseRecoveryScheduler {

	private static final Logger log = LoggerFactory.getLogger(LeaseRecoveryScheduler.class);

	private final RecoverInterruptedDeliveries recovery;

	LeaseRecoveryScheduler(RecoverInterruptedDeliveries recovery) {
		this.recovery = recovery;
	}

	@Scheduled(fixedDelayString = "${app.delivery.lease.recovery.interval:PT30S}",
			initialDelayString = "${app.delivery.lease.recovery.interval:PT30S}")
	void sweep() {
		try {
			int released = recovery.recover();
			if (released > 0) {
				log.info("Recovered {} notification events with an expired lease", released);
			}
		}
		catch (RuntimeException ex) {
			log.error("Lease recovery sweep failed; will retry on next sweep", ex);
		}
	}

}
