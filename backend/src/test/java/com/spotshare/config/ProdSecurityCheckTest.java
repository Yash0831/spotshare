package com.spotshare.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;

/**
 * The prod profile must refuse to boot when {@code JWT_SECRET} is missing or
 * too short, rather than signing tokens with a weak secret.
 */
class ProdSecurityCheckTest {

    private ProdSecurityCheck checkWithSecret(String secret) {
        AppProperties props = new AppProperties(
                new AppProperties.Cors("https://app.example.com"),
                new AppProperties.Jwt(secret, 15, 30));
        return new ProdSecurityCheck(props);
    }

    private static ApplicationArguments noArgs() {
        return new org.springframework.boot.DefaultApplicationArguments();
    }

    @Test
    void blankSecret_refusesToBoot() {
        assertThrows(IllegalStateException.class,
                () -> checkWithSecret("").run(noArgs()));
        assertThrows(IllegalStateException.class,
                () -> checkWithSecret("   ").run(noArgs()));
    }

    @Test
    void shortSecret_refusesToBoot() {
        assertThrows(IllegalStateException.class,
                () -> checkWithSecret("only-31-chars-xxxxxxxxxxxxxxx").run(noArgs()));
    }

    @Test
    void longSecret_boots() {
        assertDoesNotThrow(() -> checkWithSecret(
                "a-properly-long-random-secret-value-for-tests").run(noArgs()));
    }
}
