package com.spotshare.auth;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.spotshare.common.ErrorEnvelope;
import com.spotshare.config.AppProperties;
import com.spotshare.config.CorrelationIdFilter;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Stateless JWT security. Public: auth endpoints, the health check, and
 * actuator health. Everything else requires a valid Bearer token; anything
 * denied returns the global error envelope (never a bare 403 page).
 */
@Configuration
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtFilter;
    private final AppProperties props;
    private final ObjectMapper objectMapper;

    public SecurityConfig(JwtAuthenticationFilter jwtFilter,
                          AppProperties props,
                          ObjectMapper objectMapper) {
        this.jwtFilter = jwtFilter;
        this.props = props;
        this.objectMapper = objectMapper;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(eh -> eh.authenticationEntryPoint(authenticationEntryPoint()))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/auth/**").permitAll()
                        .requestMatchers("/api/v1/health").permitAll()
                        .requestMatchers("/actuator/health").permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        // Cost 12 per the product spec: slow enough to resist brute force,
        // fast enough for interactive login.
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        for (String origin : props.cors().allowedOrigins().split(",")) {
            String trimmed = origin.trim();
            if (!trimmed.isEmpty()) {
                config.addAllowedOrigin(trimmed);
            }
        }
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key"));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    private AuthenticationEntryPoint authenticationEntryPoint() {
        return (request, response, authException) -> {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType("application/json");
            objectMapper.writeValue(response.getWriter(), ErrorEnvelope.of(
                    "UNAUTHORIZED",
                    "Please log in to continue.",
                    correlationId(request)));
        };
    }

    private static String correlationId(HttpServletRequest request) {
        Object id = request.getAttribute(CorrelationIdFilter.ATTRIBUTE);
        return id == null ? null : id.toString();
    }
}
