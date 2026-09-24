package com.spotshare.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the {@code app.geocoding} namespace. The User-Agent identifies this
 * deployment to Nominatim, whose usage policy requires one (see
 * .env.example — set {@code NOMINATIM_USER_AGENT} to a contact email or
 * project URL in production).
 *
 * @param userAgent Nominatim User-Agent header, e.g. an app name + contact
 * @param baseUrl   Nominatim-compatible search endpoint
 */
@ConfigurationProperties(prefix = "app.geocoding")
public record GeocodingProperties(
        String userAgent,
        String baseUrl
) {
}
