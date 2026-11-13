package com.cobre.notifications.subscriptions.application;

/**
 * A new signing secret: the plain value, returned once to the client, and its encrypted form, the only one stored.
 */
public record IssuedSecret(String value, byte[] ciphertext) {

	@Override
	public String toString() {
		return "IssuedSecret[***]";
	}

}
