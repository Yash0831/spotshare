package com.spotshare.auth;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.spotshare.config.AppProperties;
import com.spotshare.user.Role;
import com.spotshare.user.User;

import io.jsonwebtoken.JwtException;

class JwtTokenServiceTest {

    private static final String SECRET = "test-secret-that-is-long-enough-for-hs256!!";

    private JwtTokenService service(Clock clock) {
        AppProperties props = new AppProperties(
                new AppProperties.Cors("http://localhost:5173"),
                new AppProperties.Jwt(SECRET, 15, 30));
        return new JwtTokenService(props, clock);
    }

    private User user() {
        return new User("ada@example.com", "hash", "Ada", "Lovelace", null, Role.USER);
    }

    @Test
    void accessTokenRoundTripsPrincipal() {
        JwtTokenService service = service(Clock.systemUTC());
        User user = user();

        String token = service.generateAccessToken(user);
        AuthenticatedUser principal = service.parseAccessToken(token);

        assertThat(principal.id()).isEqualTo(user.getId());
        assertThat(principal.email()).isEqualTo("ada@example.com");
        assertThat(principal.role()).isEqualTo(Role.USER);
    }

    @Test
    void expiredAccessTokenIsRejected() {
        Instant issued = Instant.parse("2026-01-01T00:00:00Z");
        JwtTokenService issuer = service(Clock.fixed(issued, ZoneOffset.UTC));
        String token = issuer.generateAccessToken(user());

        // 16 minutes later the 15-minute token must be dead.
        JwtTokenService verifier = service(
                Clock.fixed(issued.plusSeconds(16 * 60), ZoneOffset.UTC));

        assertThatThrownBy(() -> verifier.parseAccessToken(token))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void tokenSignedWithAnotherSecretIsRejected() {
        JwtTokenService service = service(Clock.systemUTC());
        String token = service.generateAccessToken(user());

        AppProperties otherProps = new AppProperties(
                new AppProperties.Cors("http://localhost:5173"),
                new AppProperties.Jwt("a-completely-different-secret-value-1234", 15, 30));
        JwtTokenService other = new JwtTokenService(otherProps, Clock.systemUTC());

        assertThatThrownBy(() -> other.parseAccessToken(token))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void shortSecretIsRefusedAtConstruction() {
        AppProperties props = new AppProperties(
                new AppProperties.Cors("http://localhost:5173"),
                new AppProperties.Jwt("too-short", 15, 30));

        assertThatThrownBy(() -> new JwtTokenService(props, Clock.systemUTC()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT secret");
    }

    @Test
    void refreshTokenHashIsDeterministicAndOpaque() {
        JwtTokenService service = service(Clock.systemUTC());
        String raw = service.generateRefreshToken();

        String hash1 = JwtTokenService.hashRefreshToken(raw);
        String hash2 = JwtTokenService.hashRefreshToken(raw);

        assertThat(hash1).isEqualTo(hash2);
        assertThat(hash1).doesNotContain(raw);
        assertThat(JwtTokenService.hashRefreshToken(service.generateRefreshToken()))
                .isNotEqualTo(hash1);
    }

    @Test
    void userIdIsStableAcrossCalls() {
        // Sanity: the entity generates its id once, so tokens stay consistent.
        User user = user();
        UUID first = user.getId();
        assertThat(user.getId()).isEqualTo(first);
    }
}
