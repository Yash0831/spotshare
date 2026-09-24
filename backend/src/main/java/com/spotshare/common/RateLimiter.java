package com.spotshare.common;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;

/**
 * A deliberately simple fixed-window rate limiter for the endpoints the
 * spec calls out (geocode + search, §11): at most N requests per key per
 * minute. Keys are endpoint + client IP. In-memory only — when the fleet
 * is bigger than one box this becomes a shared store, but V1 is one box.
 */
@Component
public class RateLimiter {

    private record Window(AtomicLong count, long minute) {
    }

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    /**
     * Throws a 429 {@link ApiException} when {@code key} has exceeded
     * {@code maxPerMinute} in the current minute. Otherwise counts the call.
     */
    public void check(String key, int maxPerMinute) {
        long minute = System.currentTimeMillis() / 60_000;
        Window window = windows.compute(key,
                (k, old) -> (old == null || old.minute() != minute)
                        ? new Window(new AtomicLong(0), minute)
                        : old);
        if (window.count().incrementAndGet() > maxPerMinute) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                    "You're searching a bit too fast. Please wait a moment and try again.");
        }
    }

    /** Best-effort client IP: first X-Forwarded-For entry, else remote addr. */
    public static String clientKey(HttpServletRequest request, String endpoint) {
        String forwarded = request.getHeader("X-Forwarded-For");
        String ip = (forwarded != null && !forwarded.isBlank())
                ? forwarded.split(",")[0].trim()
                : request.getRemoteAddr();
        return endpoint + "|" + ip;
    }
}
