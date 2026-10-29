package com.cobre.notifications.support;

import java.util.ArrayList;
import java.util.List;

import com.cobre.notifications.audit.application.AuditEntry;
import com.cobre.notifications.audit.application.AuditLog;

/**
 * In-memory {@link AuditLog} for use case tests.
 */
public class RecordingAuditLog implements AuditLog {

	public final List<AuditEntry> entries = new ArrayList<>();

	@Override
	public void record(AuditEntry entry) {
		entries.add(entry);
	}

}
