package com.cobre.notifications.shared.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import com.cobre.notifications.audit.application.AuditEntry;
import com.cobre.notifications.audit.application.AuditLog;
import com.cobre.notifications.shared.observability.LogContext;

/**
 * Writes each audit entry as one structured log record on the {@value #LOGGER_NAME} logger (D28). With the JSON log
 * format the fields become top-level JSON members, so the monitoring team filters on
 * {@code logger_name = audit}. The trace ID comes from the current request context and is absent for work started
 * outside a request.
 */
@Component
class StructuredAuditLog implements AuditLog {

	static final String LOGGER_NAME = "audit";

	private static final Logger log = LoggerFactory.getLogger(LOGGER_NAME);

	@Override
	public void record(AuditEntry entry) {
		try {
			var event = log.atInfo()
				.addKeyValue("audit_client_id", entry.clientId())
				.addKeyValue("audit_action", entry.action())
				.addKeyValue("audit_resource_id", entry.resourceId())
				.addKeyValue("audit_outcome", entry.outcome().wireValue());
			String traceId = MDC.get(LogContext.TRACE_ID);
			if (traceId != null) {
				event = event.addKeyValue("audit_trace_id", traceId);
			}
			event.log("audit {} {} {}", entry.action(), entry.resourceId(), entry.outcome().wireValue());
		}
		catch (RuntimeException ex) {
			// Auditing must never turn a completed operation into an error.
			LoggerFactory.getLogger(StructuredAuditLog.class).error("Audit entry could not be written", ex);
		}
	}

}
