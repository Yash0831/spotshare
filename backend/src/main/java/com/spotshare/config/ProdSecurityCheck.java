package com.spotshare.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Fail-fast guard: under the {@code prod} profile the application refuses to
 * boot when {@code JWT_SECRET} is missing or too short, instead of signing
 * tokens with a weak or absent secret.
 */
@Component
@Profile("prod")
public class ProdSecurityCheck implements ApplicationRunner {

    private final AppProperties props;

    public ProdSecurityCheck(AppProperties props) {
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        String secret = props.jwt().secret();
        if (secret == null || secret.isBlank() || secret.length() < 32) {
            throw new IllegalStateException(
                    "Refusing to boot with profile 'prod': JWT_SECRET must be set to a "
                    + "long random value (at least 32 characters).");
        }
    }
}
