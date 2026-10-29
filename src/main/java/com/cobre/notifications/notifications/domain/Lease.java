package com.cobre.notifications.notifications.domain;

import java.util.Objects;

/**
 * The right of one worker to record the result of the current attempt of a notification event. {@code generation} is
 * the fencing token: every claim and every release increases it, and every write of the attempt result is conditional
 * on it, so a worker whose lease was taken over cannot overwrite the newer result.
 *
 * @param owner the worker instance, for diagnostics
 * @param generation the fencing token returned by the claim
 */
public record Lease(String owner, long generation) {

	public Lease {
		Objects.requireNonNull(owner);
	}

}
