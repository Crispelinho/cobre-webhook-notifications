package com.cobre.notifications.notifications.application;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.cobre.notifications.audit.application.AuditEntry;
import com.cobre.notifications.audit.application.AuditLog;
import com.cobre.notifications.audit.application.AuditOutcome;
import com.cobre.notifications.notifications.application.port.in.ReplayNotificationEvent;
import com.cobre.notifications.notifications.application.port.out.ReplayStore;
import com.cobre.notifications.notifications.domain.ClientId;
import com.cobre.notifications.notifications.domain.NotReplayableException;
import com.cobre.notifications.notifications.domain.NotificationEvent;
import com.cobre.notifications.notifications.domain.NotificationEventId;

/**
 * Replays a failed notification event. The store writes the new round and its outbox row in one transaction and only if
 * the version still matches, so of several simultaneous replays exactly one wins. Logs carry ids and rounds only.
 */
public class ReplayNotificationEventUseCase implements ReplayNotificationEvent {

	private static final Logger log = LoggerFactory.getLogger(ReplayNotificationEventUseCase.class);

	private final ReplayStore store;

	private final Clock clock;

	private final AuditLog audit;

	public ReplayNotificationEventUseCase(ReplayStore store, Clock clock, AuditLog audit) {
		this.store = Objects.requireNonNull(store);
		this.clock = Objects.requireNonNull(clock);
		this.audit = Objects.requireNonNull(audit);
	}

	@Override
	public Optional<NotificationEvent> replay(ClientId clientId, NotificationEventId id) {
		Objects.requireNonNull(clientId);
		Objects.requireNonNull(id);
		try {
			Optional<NotificationEvent> replayed = doReplay(id, clientId);
			audit(clientId, id, replayed.isPresent() ? AuditOutcome.SUCCESS : AuditOutcome.NOT_FOUND);
			return replayed;
		}
		catch (NotReplayableException ex) {
			audit(clientId, id, AuditOutcome.REJECTED);
			throw ex;
		}
	}

	private Optional<NotificationEvent> doReplay(NotificationEventId id, ClientId clientId) {
		Optional<NotificationEvent> found = store.findById(clientId, id);
		if (found.isEmpty()) {
			return Optional.empty();
		}
		NotificationEvent before = found.get();
		NotificationEvent after = before.replay();
		if (!store.startReplay(before, after, clock.instant())) {
			log.info("Replay of notification event {} lost to a concurrent change", id.value());
			throw new NotReplayableException();
		}
		log.info("Notification event {} replayed: round {}", id.value(), after.round());
		return Optional.of(after);
	}

	private void audit(ClientId clientId, NotificationEventId id, AuditOutcome outcome) {
		audit.record(new AuditEntry(clientId.value(), AuditEntry.REPLAY, id.value(), outcome));
	}

}
