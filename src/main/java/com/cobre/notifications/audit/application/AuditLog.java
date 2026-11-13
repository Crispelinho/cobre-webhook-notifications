package com.cobre.notifications.audit.application;

/**
 * Records who did what to which resource and how it ended. One entry per sensitive operation: replays and
 * subscription creation, update and deactivation. Implementations add the trace ID of the current request and must
 * never throw into the use case: an audit failure must not turn a completed operation into an error.
 */
public interface AuditLog {

	void record(AuditEntry entry);

}
