package com.cobre.notifications.audit.application;

import java.util.Locale;

/**
 * How an audited operation ended.
 */
public enum AuditOutcome {

	/** The operation was carried out. */
	SUCCESS,
	/** The rules refused the operation (for example, replaying a notification event that is not failed). */
	REJECTED,
	/** The resource does not exist for this client; the same answer as for another client's resource. */
	NOT_FOUND;

	public String wireValue() {
		return name().toLowerCase(Locale.ROOT);
	}

}
