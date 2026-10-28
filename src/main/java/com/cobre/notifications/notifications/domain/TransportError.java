package com.cobre.notifications.notifications.domain;

/**
 * A delivery attempt that produced no HTTP response. {@link #errorType()} is the code stored with the attempt; it never
 * carries event content, destinations or secrets.
 */
public enum TransportError {

	TIMEOUT("timeout"), CONNECTION("connection_error"), TLS("tls_error"), INVALID_DESTINATION("invalid_destination");

	private final String errorType;

	TransportError(String errorType) {
		this.errorType = errorType;
	}

	public String errorType() {
		return errorType;
	}

}
