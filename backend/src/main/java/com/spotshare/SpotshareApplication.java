package com.spotshare;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import com.spotshare.config.AppProperties;
import com.spotshare.config.GeocodingProperties;

/**
 * SpotShare parking-sharing marketplace API.
 *
 * <p>Phase 4: driver discovery — PostGIS nearby search (map + list),
 * privacy-safe public DTOs, and Nominatim-backed geocoding behind the
 * GeocodingProvider interface.
 */
@SpringBootApplication
@EnableConfigurationProperties({AppProperties.class, GeocodingProperties.class})
public class SpotshareApplication {

    public static void main(String[] args) {
        SpringApplication.run(SpotshareApplication.class, args);
    }
}
