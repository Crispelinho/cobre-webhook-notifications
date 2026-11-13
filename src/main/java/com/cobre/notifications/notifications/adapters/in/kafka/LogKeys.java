package com.cobre.notifications.notifications.adapters.in.kafka;

/**
 * MDC keys shared by the log lines of one dispatch message. They must match
 * {@code com.cobre.notifications.shared.observability.LogContext}.
 */
final class LogKeys {

	static final String NOTIFICATION_EVENT_ID = "notification_event_id";

	private LogKeys() {
	}

}
