package com.spotshare.common;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import com.spotshare.config.AppProperties;

/**
 * The health endpoint is the load balancer's liveness probe: it must answer
 * 200 without authentication and carry no sensitive data.
 */
@WebMvcTest(HealthController.class)
@Import(SecurityConfig.class)
class HealthControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private JwtAuthenticationFilter jwtFilter;

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

    @org.junit.jupiter.api.BeforeEach
    void passThroughJwtFilter() throws Exception {
        // The mock filter would otherwise swallow the request; let it through
        // so the test exercises the real permitAll rule for /api/v1/health.
        org.mockito.Mockito.doAnswer(inv -> {
            ((jakarta.servlet.FilterChain) inv.getArgument(2))
                    .doFilter(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(jwtFilter).doFilter(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void health_isPublicAndMinimal() throws Exception {
        mvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.service").value("spotshare-api"));
    }
}
