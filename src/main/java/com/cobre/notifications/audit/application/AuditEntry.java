package com.cobre.notifications.audit.application;

import java.util.Objects;

/**
 * One audit entry. It names the resource by ID only: never request or event content, destinations or secrets.
 *
 * @param clientId the acting client, taken from the access token
 * @param action what was attempted, for example {@code notification_event.replay}
 * @param resourceId ID of the resource the action targeted
 */
public record AuditEntry(String clientId, String action, String resourceId, AuditOutcome outcome) {

	public static final String REPLAY = "notification_event.replay";

	public static final String SUBSCRIPTION_CREATE = "subscription.create";

	public static final String SUBSCRIPTION_UPDATE = "subscription.update";

	public static final String SUBSCRIPTION_DEACTIVATE = "subscription.deactivate";

	public AuditEntry {
		Objects.requireNonNull(clientId);
		Objects.requireNonNull(action);
		Objects.requireNonNull(resourceId);
		Objects.requireNonNull(outcome);
	}

}
