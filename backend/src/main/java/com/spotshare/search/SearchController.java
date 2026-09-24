package com.spotshare.search;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.spotshare.common.RateLimiter;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

/**
 * Driver discovery endpoints. Both are public (no login required): the
 * privacy-safe DTOs (§11) make browsing safe for guests, and reserving
 * (Phase 5) will require an account. Rate-limited per the spec.
 */
@RestController
@RequestMapping("/api/v1")
public class SearchController {

    private static final int SEARCH_LIMIT_PER_MINUTE = 120;
    private static final int GEOCODE_LIMIT_PER_MINUTE = 30;

    private final SearchService search;
    private final RateLimiter rateLimiter;

    public SearchController(SearchService search, RateLimiter rateLimiter) {
        this.search = search;
        this.rateLimiter = rateLimiter;
    }

    /**
     * Nearby spaces whose share window fully contains
     * {@code [arrival, departure)}, nearest first. Radius is miles.
     */
    @GetMapping("/spaces/search")
    public SearchResponseDto search(@Valid @ModelAttribute SearchRequest request,
                                    HttpServletRequest http) {
        rateLimiter.check(RateLimiter.clientKey(http, "search"), SEARCH_LIMIT_PER_MINUTE);
        return search.search(request);
    }

    /** Address → coordinate candidates for the destination search box. */
    @GetMapping("/geocode")
    public List<GeocodeCandidate> geocode(@RequestParam("q") String query,
                                         HttpServletRequest http) {
        rateLimiter.check(RateLimiter.clientKey(http, "geocode"), GEOCODE_LIMIT_PER_MINUTE);
        return search.geocode(query);
    }
}
