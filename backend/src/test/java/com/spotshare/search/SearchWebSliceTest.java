package com.spotshare.search;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;

import com.spotshare.auth.JwtAuthenticationFilter;
import com.spotshare.auth.SecurityConfig;
import com.spotshare.common.ApiException;
import com.spotshare.common.RateLimiter;
import com.spotshare.config.AppProperties;

import jakarta.servlet.FilterChain;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web-layer slice for discovery: query-param binding onto
 * {@link SearchRequest}, Bean Validation, the public security rules, and the
 * error envelope — no database. Service logic is covered by
 * {@link SearchServiceTest}; the PostGIS query runs in
 * {@link SearchIntegrationTest}.
 */
@WebMvcTest(SearchController.class)
@Import(SecurityConfig.class)
class SearchWebSliceTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private SearchService search;

    @MockBean
    private JwtAuthenticationFilter jwtFilter;

    @MockBean
    private RateLimiter rateLimiter;

    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        AppProperties appProperties() {
            return new AppProperties(
                    new AppProperties.Cors("http://localhost:5173"),
                    new AppProperties.Jwt("test-secret-that-is-long-enough-for-hs256!!", 15, 30));
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        doAnswer(inv -> {
            ((FilterChain) inv.getArgument(2))
                    .doFilter(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(jwtFilter).doFilter(any(), any(), any());
    }

    private static final String BASE =
            "/api/v1/spaces/search?lat=41.8858&lng=-87.6189"
                    + "&arrival=2030-06-01T13:00:00Z&departure=2030-06-01T15:00:00Z";

    private SearchResponseDto emptyPage() {
        return new SearchResponseDto(List.of(), 0, 20, false);
    }

    @Test
    void search_isPublic_noTokenStill200() throws Exception {
        given(search.search(any(SearchRequest.class))).willReturn(emptyPage());

        mvc.perform(get(BASE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results").isArray())
                .andExpect(jsonPath("$.hasMore").value(false));
    }

    @Test
    void search_missingLat_returns400() throws Exception {
        mvc.perform(get("/api/v1/spaces/search?lng=-87.6189"
                        + "&arrival=2030-06-01T13:00:00Z&departure=2030-06-01T15:00:00Z"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void search_latOutOfRange_returns400() throws Exception {
        mvc.perform(get(BASE.replace("lat=41.8858", "lat=200")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void search_radiusTooLarge_returns400() throws Exception {
        mvc.perform(get(BASE + "&radiusMiles=100"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void search_missingPeriod_returns400() throws Exception {
        mvc.perform(get("/api/v1/spaces/search?lat=41.8858&lng=-87.6189"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void search_arrivalNotBeforeDeparture_returns422Envelope() throws Exception {
        given(search.search(any(SearchRequest.class)))
                .willThrow(ApiException.unprocessable("INVALID_SEARCH_PERIOD",
                        "Your arrival time must be before your departure time."));

        mvc.perform(get(BASE))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_SEARCH_PERIOD"))
                .andExpect(jsonPath("$.correlationId").exists());
    }

    @Test
    void search_valid_bindsAllParams() throws Exception {
        given(search.search(any(SearchRequest.class))).willAnswer(inv -> {
            SearchRequest req = inv.getArgument(0);
            // Spot-check the query-param binding, including enums + page.
            org.assertj.core.api.Assertions.assertThat(req.lat())
                    .isEqualByComparingTo(new BigDecimal("41.8858"));
            org.assertj.core.api.Assertions.assertThat(req.radiusMiles()).isEqualTo(10.0);
            org.assertj.core.api.Assertions.assertThat(req.vehicleSize())
                    .isEqualTo(com.spotshare.parking.VehicleSize.SUV);
            org.assertj.core.api.Assertions.assertThat(req.arrival())
                    .isEqualTo(Instant.parse("2030-06-01T13:00:00Z"));
            return emptyPage();
        });

        mvc.perform(get(BASE + "&radiusMiles=10&maxPrice=12.50&covered=true"
                        + "&evCharging=false&vehicleSize=SUV&page=1&size=10"))
                .andExpect(status().isOk());
    }

    @Test
    void geocode_isPublic_noTokenStill200() throws Exception {
        given(search.geocode("West Loop")).willReturn(List.of(
                new GeocodeCandidate("West Loop, Chicago, IL", 41.885, -87.619)));

        mvc.perform(get("/api/v1/geocode").queryParam("q", "West Loop"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].displayName").value("West Loop, Chicago, IL"))
                .andExpect(jsonPath("$[0].latitude").value(41.885));
    }

    @Test
    void geocode_missingQuery_returns400() throws Exception {
        mvc.perform(get("/api/v1/geocode"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void geocode_shortQuery_returns400Envelope() throws Exception {
        given(search.geocode("ab")).willThrow(ApiException.badRequest("QUERY_TOO_SHORT",
                "Type at least 3 characters to search for a place."));

        mvc.perform(get("/api/v1/geocode").queryParam("q", "ab"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("QUERY_TOO_SHORT"));
    }
}
