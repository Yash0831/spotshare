package com.spotshare.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * The fixed-window limiter: N calls pass, the N+1th gets the friendly 429,
 * and a forwarded client IP is honored as the key.
 */
class RateLimiterTest {

    private final RateLimiter limiter = new RateLimiter();

    @Test
    void allowsUpToTheLimit_thenRejects() {
        String key = "search|" + UUID.randomUUID();

        for (int i = 0; i < 5; i++) {
            limiter.check(key, 5);
        }
        assertThatThrownBy(() -> limiter.check(key, 5))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException ae = (ApiException) e;
                    assertThat(ae.getCode()).isEqualTo("RATE_LIMITED");
                    assertThat(ae.getStatus().value()).isEqualTo(429);
                });
    }

    @Test
    void differentKeys_haveIndependentWindows() {
        String key = "geocode|" + UUID.randomUUID();
        limiter.check(key, 1);
        // A different key is unaffected.
        limiter.check("geocode|" + UUID.randomUUID(), 1);
    }

    @Test
    void clientKey_prefersForwardedFor() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-For", "203.0.113.7, 70.41.3.18");
        request.setRemoteAddr("10.0.0.1");

        assertThat(RateLimiter.clientKey(request, "search")).isEqualTo("search|203.0.113.7");
    }
}
