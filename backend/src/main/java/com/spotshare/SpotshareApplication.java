package com.spotshare;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import com.spotshare.config.AppProperties;

/**
 * SpotShare parking-sharing marketplace API.
 *
 * <p>Phase 1 foundation: boots the web skeleton (correlation-ID filter, global
 * error envelope, health endpoint, env-driven config). No domain logic yet —
 * module packages under {@code com.spotshare} carry a package-info.java each
 * describing the phase that will fill them in.
 */
@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
public class SpotshareApplication {

    public static void main(String[] args) {
        SpringApplication.run(SpotshareApplication.class, args);
    }
}
