package com.spotshare.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides the {@link Clock} every time-sensitive service uses instead of
 * calling {@code Instant.now()} directly, so expiry and scheduling logic is
 * unit-testable with fixed clocks.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
