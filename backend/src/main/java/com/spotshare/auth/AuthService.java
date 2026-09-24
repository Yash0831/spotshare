package com.spotshare.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Locale;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.spotshare.auth.dto.AuthResponse;
import com.spotshare.auth.dto.LoginRequest;
import com.spotshare.auth.dto.RegisterRequest;
import com.spotshare.auth.dto.UserDto;
import com.spotshare.common.ApiException;
import com.spotshare.config.AppProperties;
import com.spotshare.user.RefreshToken;
import com.spotshare.user.RefreshTokenRepository;
import com.spotshare.user.Role;
import com.spotshare.user.User;
import com.spotshare.user.UserRepository;

/**
 * Registration, login, refresh-token rotation, and logout.
 *
 * <p>Refresh rotation is single-use: every /refresh revokes the presented token
 * and issues a fresh pair, so a stolen refresh token is useful at most once —
 * and reuse of an already-rotated token fails loudly.
 */
@Service
public class AuthService {

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService tokens;
    private final Duration refreshTtl;
    private final Clock clock;

    public AuthService(UserRepository users,
                       RefreshTokenRepository refreshTokens,
                       PasswordEncoder passwordEncoder,
                       JwtTokenService tokens,
                       AppProperties props,
                       Clock clock) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.passwordEncoder = passwordEncoder;
        this.tokens = tokens;
        this.refreshTtl = Duration.ofDays(props.jwt().refreshTokenDays());
        this.clock = clock;
    }

    @Transactional
    public AuthResponse register(RegisterRequest req) {
        String email = req.email().trim().toLowerCase(Locale.ROOT);
        if (users.findByEmail(email).isPresent()) {
            throw ApiException.conflict("EMAIL_TAKEN",
                    "An account with this email already exists. Try logging in instead.");
        }
        User user = new User(email,
                passwordEncoder.encode(req.password()),
                req.firstName().trim(),
                req.lastName().trim(),
                req.phone() == null || req.phone().isBlank() ? null : req.phone().trim(),
                Role.USER);
        users.save(user);
        return issueTokens(user);
    }

    @Transactional
    public AuthResponse login(LoginRequest req) {
        String email = req.email().trim().toLowerCase(Locale.ROOT);
        User user = users.findByEmail(email)
                .filter(u -> passwordEncoder.matches(req.password(), u.getPasswordHash()))
                .orElseThrow(() -> ApiException.unauthorized("INVALID_CREDENTIALS",
                        "Email or password is incorrect."));
        return issueTokens(user);
    }

    /**
     * Rotates a refresh token: the presented token is revoked and a new pair is
     * issued. Re-presenting an already-used token fails — it was revoked.
     */
    @Transactional
    public AuthResponse refresh(String rawToken) {
        RefreshToken stored = refreshTokens
                .findByTokenHash(JwtTokenService.hashRefreshToken(rawToken))
                .filter(t -> !t.isRevoked()
                        && t.getExpiresAt().isAfter(OffsetDateTime.now(clock)))
                .orElseThrow(() -> ApiException.unauthorized("INVALID_REFRESH_TOKEN",
                        "Your session has expired. Please log in again."));
        stored.setRevoked(true);
        User user = users.findById(stored.getUserId())
                .orElseThrow(() -> ApiException.unauthorized("INVALID_REFRESH_TOKEN",
                        "Your session has expired. Please log in again."));
        return issueTokens(user);
    }

    /** Revokes a refresh token. Idempotent: unknown tokens still return success. */
    @Transactional
    public void logout(String rawToken) {
        refreshTokens.findByTokenHash(JwtTokenService.hashRefreshToken(rawToken))
                .ifPresent(t -> t.setRevoked(true));
    }

    private AuthResponse issueTokens(User user) {
        String refresh = tokens.generateRefreshToken();
        refreshTokens.save(new RefreshToken(user.getId(),
                JwtTokenService.hashRefreshToken(refresh),
                OffsetDateTime.now(clock).plus(refreshTtl)));
        return AuthResponse.bearer(tokens.generateAccessToken(user), refresh,
                tokens.accessTtlSeconds(), toDto(user));
    }

    public static UserDto toDto(User user) {
        return new UserDto(user.getId(), user.getEmail(), user.getFirstName(),
                user.getLastName(), user.getPhone(), user.getRole());
    }
}
