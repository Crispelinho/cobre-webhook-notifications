package com.cobre.notifications.subscriptions.adapters.in.rest;

/**
 * A request body field that the operation does not accept, such as {@code client_id} or, on a patch, {@code url}.
 * Mapped to 400.
 */
class UnknownFieldException extends RuntimeException {

	private final String field;

	UnknownFieldException(String field) {
		super("unknown field");
		this.field = field;
	}

	String field() {
		return field;
	}

}
