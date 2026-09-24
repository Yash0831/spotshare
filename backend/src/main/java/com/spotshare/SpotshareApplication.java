package com.spotshare;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import com.spotshare.config.AppProperties;

/**
 * SpotShare parking-sharing marketplace API.
 *
 * <p>Phase 3: parking space inventory and the signature "I'm leaving / Share My
 * Spot" flow — hosts create temporary availability windows (free or hourly,
 * min 30 minutes, auto-expiring at the return time) and spaces carry a
 * derived display state (OFFLINE / PRIVATE / AVAILABLE / RETURNING).
 */
@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
public class SpotshareApplication {

    public static void main(String[] args) {
        SpringApplication.run(SpotshareApplication.class, args);
    }
}
