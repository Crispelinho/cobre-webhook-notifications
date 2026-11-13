package com.cobre.notifications.notifications.adapters.in.rest;

/**
 * Request parameters that the domain rejected. Mapped to 400.
 */
class InvalidRequestException extends RuntimeException {

	InvalidRequestException(String message) {
		super(message);
	}

}
