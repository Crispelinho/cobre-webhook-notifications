package com.cobre.notifications.shared.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.stream.Collectors;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import com.cobre.notifications.audit.application.AuditEntry;
import com.cobre.notifications.audit.application.AuditOutcome;

class StructuredAuditLogTests {

	private final StructuredAuditLog audit = new StructuredAuditLog();

	private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

	private final Logger logger = (Logger) LoggerFactory.getLogger(StructuredAuditLog.LOGGER_NAME);

	@BeforeEach
	void attach() {
		appender.start();
		logger.addAppender(appender);
	}

	@AfterEach
	void detach() {
		logger.detachAppender(appender);
		MDC.clear();
	}

	@Test
	void anEntryBecomesOneStructuredRecordWithActorActionResourceOutcomeAndTrace() {
		MDC.put("traceId", "4bf92f3577b34da6a3ce929d0e0e4736");

		audit.record(new AuditEntry("CLIENT002", "notification_event.replay", "EVT003", AuditOutcome.SUCCESS));

		assertThat(appender.list).hasSize(1);
		assertThat(fields(appender.list.getFirst())).containsExactlyInAnyOrderEntriesOf(Map.of("audit_client_id",
				"CLIENT002", "audit_action", "notification_event.replay", "audit_resource_id", "EVT003",
				"audit_outcome", "success", "audit_trace_id", "4bf92f3577b34da6a3ce929d0e0e4736"));
	}

	@Test
	void aRejectedOutcomeIsRecordedAsRejected() {
		audit.record(new AuditEntry("CLIENT001", "notification_event.replay", "EVT001", AuditOutcome.REJECTED));

		assertThat(fields(appender.list.getFirst())).containsEntry("audit_outcome", "rejected")
			.doesNotContainKey("audit_trace_id");
	}

	private static Map<String, String> fields(ILoggingEvent event) {
		return event.getKeyValuePairs()
			.stream()
			.collect(Collectors.toMap(pair -> pair.key, pair -> String.valueOf(pair.value)));
	}

}
