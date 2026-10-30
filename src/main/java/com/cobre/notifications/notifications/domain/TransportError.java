package com.cobre.notifications.notifications.domain;

/**
 * A delivery attempt that produced no HTTP response. {@link #errorType()} is the code stored with the attempt; it never
 * carries event content, destinations or secrets.
 */
public enum TransportError {

	TIMEOUT("timeout"), CONNECTION("connection_error"), TLS("tls_error"), INVALID_DESTINATION("invalid_destination"),
	/** The host name did not resolve at send time (NXDOMAIN, SERVFAIL or timeout). The cause may be transient. */
	DNS("dns_error"),
	/** The subscription secret could not be decrypted, so the request cannot be signed and is never sent. */
	SIGNING_ERROR("signing_error"),
	/** The subscription has no secret: an unsigned delivery is never sent. */
	MISSING_SECRET("missing_secret");

	private final String errorType;

	TransportError(String errorType) {
		this.errorType = errorType;
	}

	public String errorType() {
		return errorType;
	}

}
