package com.spotshare.auth;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.spotshare.auth.dto.LoginRequest;
import com.spotshare.auth.dto.RegisterRequest;
import com.spotshare.common.ApiException;
import com.spotshare.config.AppProperties;
import com.spotshare.user.RefreshToken;
import com.spotshare.user.RefreshTokenRepository;
import com.spotshare.user.Role;
import com.spotshare.user.User;
import com.spotshare.user.UserRepository;

/**
 * AuthService behavior without a database: registration, login, refresh-token
 * rotation, and logout, with the repositories mocked. The database-backed
 * variant lives in {@link AuthIntegrationTest}.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-24T12:00:00Z");

    @Mock
    private UserRepository users;

    @Mock
    private RefreshTokenRepository refreshTokens;

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(12);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private AuthService authService;

    @BeforeEach
    void setUp() {
        AppProperties props = new AppProperties(
                new AppProperties.Cors("http://localhost:5173"),
                new AppProperties.Jwt("test-secret-that-is-long-enough-for-hs256!!", 15, 30));
        JwtTokenService tokens = new JwtTokenService(props, clock);
        authService = new AuthService(users, refreshTokens, passwordEncoder, tokens, props, clock);
    }

    private RegisterRequest registerRequest(String email) {
        return new RegisterRequest(email, "correct-horse-9", "Sam", "Reyes", "+13125550100");
    }

    @Test
    void register_normalizesEmail_hashesPassword_andStoresTokenHash() {
        given(users.findByEmail("sam@example.com")).willReturn(Optional.empty());

        var response = authService.register(registerRequest("Sam@Example.com"));

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(users).save(userCaptor.capture());
        User saved = userCaptor.getValue();
        assertThat(saved.getEmail()).isEqualTo("sam@example.com");
        assertThat(saved.getRole()).isEqualTo(Role.USER);
        assertThat(passwordEncoder.matches("correct-horse-9", saved.getPasswordHash())).isTrue();
        assertThat(saved.getPasswordHash()).startsWith("$2a$12$");

        // The stored refresh token is a hash, never the raw token.
        ArgumentCaptor<RefreshToken> tokenCaptor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokens).save(tokenCaptor.capture());
        RefreshToken stored = tokenCaptor.getValue();
        assertThat(stored.getTokenHash())
                .isEqualTo(JwtTokenService.hashRefreshToken(response.refreshToken()));
        assertThat(stored.getTokenHash()).doesNotContain(response.refreshToken());
        assertThat(stored.getExpiresAt())
                .isEqualTo(OffsetDateTime.ofInstant(NOW.plusSeconds(30L * 24 * 60 * 60), ZoneOffset.UTC));

        assertThat(response.accessToken()).isNotBlank();
        assertThat(response.user().email()).isEqualTo("sam@example.com");
    }

    @Test
    void register_duplicateEmail_isConflict() {
        given(users.findByEmail("sam@example.com"))
                .willReturn(Optional.of(new User("sam@example.com", "hash",
                        "Sam", "Reyes", null, Role.USER)));

        assertThatThrownBy(() -> authService.register(registerRequest("sam@example.com")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException api = (ApiException) e;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(api.getCode()).isEqualTo("EMAIL_TAKEN");
                });
    }

    @Test
    void login_success_and_failures() {
        User user = new User("sam@example.com", passwordEncoder.encode("correct-horse-9"),
                "Sam", "Reyes", null, Role.USER);
        given(users.findByEmail("sam@example.com")).willReturn(Optional.of(user));

        var response = authService.login(new LoginRequest("sam@example.com", "correct-horse-9"));
        assertThat(response.accessToken()).isNotBlank();
        assertThat(response.user().email()).isEqualTo("sam@example.com");

        // Wrong password and unknown email produce the identical 401.
        assertThatThrownBy(() -> authService.login(new LoginRequest("sam@example.com", "wrong")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode())
                        .isEqualTo("INVALID_CREDENTIALS"));
        given(users.findByEmail("nobody@example.com")).willReturn(Optional.empty());
        assertThatThrownBy(() -> authService.login(new LoginRequest("nobody@example.com", "wrong")))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> {
                    ApiException api = (ApiException) e;
                    assertThat(api.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(api.getCode()).isEqualTo("INVALID_CREDENTIALS");
                });
    }

    @Test
    void refresh_rotates_and_rejectsTheOldToken() {
        User user = new User("sam@example.com", passwordEncoder.encode("correct-horse-9"),
                "Sam", "Reyes", null, Role.USER);
        given(users.findByEmail("sam@example.com")).willReturn(Optional.of(user));
        var login = authService.login(new LoginRequest("sam@example.com", "correct-horse-9"));
        String firstRefresh = login.refreshToken();

        ArgumentCaptor<RefreshToken> tokenCaptor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokens).save(tokenCaptor.capture());
        RefreshToken stored = tokenCaptor.getValue();
        given(users.findById(user.getId())).willReturn(Optional.of(user));
        given(refreshTokens.findByTokenHash(stored.getTokenHash()))
                .willReturn(Optional.of(stored));

        var rotated = authService.refresh(firstRefresh);

        // The presented token was revoked; a fresh pair was issued.
        assertThat(stored.isRevoked()).isTrue();
        assertThat(rotated.refreshToken()).isNotEqualTo(firstRefresh);
        assertThat(rotated.accessToken()).isNotBlank();

        // Re-presenting the rotated token now fails: it is revoked.
        assertThatThrownBy(() -> authService.refresh(firstRefresh))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode())
                        .isEqualTo("INVALID_REFRESH_TOKEN"));
    }

    @Test
    void refresh_rejectsExpiredAndUnknownTokens() {
        String raw = "raw-token";
        RefreshToken expired = new RefreshToken(java.util.UUID.randomUUID(),
                JwtTokenService.hashRefreshToken(raw),
                OffsetDateTime.ofInstant(NOW.minusSeconds(60), ZoneOffset.UTC));
        given(refreshTokens.findByTokenHash(expired.getTokenHash()))
                .willReturn(Optional.of(expired));

        assertThatThrownBy(() -> authService.refresh(raw))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode())
                        .isEqualTo("INVALID_REFRESH_TOKEN"));

        given(refreshTokens.findByTokenHash(JwtTokenService.hashRefreshToken("anything")))
                .willReturn(Optional.empty());
        assertThatThrownBy(() -> authService.refresh("anything"))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getCode())
                        .isEqualTo("INVALID_REFRESH_TOKEN"));
    }

    @Test
    void logout_revokes_andIsIdempotent() {
        RefreshToken stored = new RefreshToken(java.util.UUID.randomUUID(),
                JwtTokenService.hashRefreshToken("raw"), OffsetDateTime.now(clock).plusDays(1));
        given(refreshTokens.findByTokenHash(stored.getTokenHash()))
                .willReturn(Optional.of(stored));

        authService.logout("raw");
        assertThat(stored.isRevoked()).isTrue();

        // Unknown tokens are a no-op success.
        given(refreshTokens.findByTokenHash(JwtTokenService.hashRefreshToken("unknown-token")))
                .willReturn(Optional.empty());
        authService.logout("unknown-token");
    }
}
