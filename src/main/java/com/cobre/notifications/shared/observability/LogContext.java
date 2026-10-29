package com.cobre.notifications.shared.observability;

/**
 * Names of the MDC entries that structured logs carry. {@link #TRACE_ID} is written by Micrometer Tracing; the other
 * two are set where the identifier becomes known (the request filter and the Kafka listeners). Only identifiers are
 * ever put here, never event content, tokens or secrets.
 */
public final class LogContext {

	public static final String TRACE_ID = "traceId";

	public static final String CLIENT_ID = "client_id";

	public static final String NOTIFICATION_EVENT_ID = "notification_event_id";

	private LogContext() {
	}

}
