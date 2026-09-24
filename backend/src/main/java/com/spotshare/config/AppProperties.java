package com.spotshare.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the {@code app.*} namespace from application.yml. All values are
 * env-driven with safe development defaults; production values come from
 * environment variables (see .env.example).
 *
 * @param corsAllowedOrigins comma-separated frontend origins for CORS
 * @param jwtSecret          HMAC secret for JWTs; must be set to a long random
 *                           value in production (fail-fast check in a later phase)
 * @param nominatimUserAgent User-Agent header identifying SpotShare to Nominatim,
 *                           per the Nominatim usage policy
 * @param s3                 S3-compatible object storage settings for parking photos
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        Cors cors,
        Jwt jwt,
        Nominatim nominatim,
        S3 s3
) {
    public record Cors(String allowedOrigins) {
    }

    public record Jwt(String secret) {
    }

    public record Nominatim(String userAgent) {
    }

    public record S3(String endpoint, String region, String bucket, String accessKey, String secretKey) {

        /** True when every storage setting is present (photo upload is enabled). */
        public boolean isConfigured() {
            return endpoint != null && !endpoint.isBlank()
                    && bucket != null && !bucket.isBlank()
                    && accessKey != null && !accessKey.isBlank()
                    && secretKey != null && !secretKey.isBlank();
        }
    }
}
