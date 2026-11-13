package com.cobre.notifications.shared.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;

/**
 * Settings to validate access tokens. Only the issuer's public key is configured here; the private key never reaches
 * this service.
 */
@ConfigurationProperties("app.security.jwt")
public record JwtProperties(Resource publicKeyLocation, String issuer, String audience) {
}
