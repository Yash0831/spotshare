package com.spotshare.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the {@code app.*} namespace from application.yml. All values are
 * env-driven with safe development defaults; production values come from
 * environment variables (see .env.example).
 *
 * @param corsAllowedOrigins comma-separated frontend origins for CORS
 * @param jwtSecret          HMAC secret for JWTs; must be a long random value in
 *                           production (fail-fast check under the prod profile)
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        Cors cors,
        Jwt jwt
) {
    public record Cors(String allowedOrigins) {
    }

    public record Jwt(String secret, int accessTokenMinutes, int refreshTokenDays) {
    }
}
