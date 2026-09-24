package com.spotshare;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.spotshare.config.AppProperties;
import com.spotshare.config.GeocodingProperties;

/**
 * SpotShare parking-sharing marketplace API.
 *
 * <p>Phase 6: active parking — reservation completion scheduler, host
 * cancellation, host arrivals view, and the countdown/15-minute-reminder
 * frontend.
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({AppProperties.class, GeocodingProperties.class})
public class SpotshareApplication {

    public static void main(String[] args) {
        SpringApplication.run(SpotshareApplication.class, args);
    }
}
