package com.spotshare.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.springframework.stereotype.Service;

import com.spotshare.config.AppProperties;
import com.spotshare.user.Role;
import com.spotshare.user.User;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * Issues and validates JWT access tokens (jjwt) and opaque refresh tokens.
 *
 * <ul>
 *   <li>Access token: signed HS256 JWT, subject = user id, 15-minute TTL.</li>
 *   <li>Refresh token: 256-bit random opaque string; only its SHA-256 hash is
 *       ever stored (see {@link AuthService} / {@code refresh_tokens}).</li>
 * </ul>
 *
 * <p>All time math goes through the injected {@link Clock} so expiry is
 * unit-testable.
 */
@Service
public class JwtTokenService {

    private final SecretKey signingKey;
    private final Duration accessTtl;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public JwtTokenService(AppProperties props, Clock clock) {
        byte[] secret = props.jwt().secret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) {
            throw new IllegalStateException(
                    "JWT secret must be at least 32 bytes. Set JWT_SECRET to a long random value.");
        }
        this.signingKey = Keys.hmacShaKeyFor(secret);
        this.accessTtl = Duration.ofMinutes(props.jwt().accessTokenMinutes());
        this.clock = clock;
    }

    /** Issues a signed access token for the given user. */
    public String generateAccessToken(User user) {
        Instant now = clock.instant();
        return Jwts.builder()
                .subject(user.getId().toString())
                .claim("email", user.getEmail())
                .claim("role", user.getRole().name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessTtl)))
                .signWith(signingKey)
                .compact();
    }

    /**
     * Validates a Bearer token and returns the principal it carries.
     *
     * @throws JwtException when the token is expired, malformed, or badly signed
     */
    public AuthenticatedUser parseAccessToken(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(signingKey)
                .clock(() -> Date.from(clock.instant()))
                .build()
                .parseSignedClaims(token)
                .getPayload();
        return new AuthenticatedUser(
                UUID.fromString(claims.getSubject()),
                claims.get("email", String.class),
                Role.valueOf(claims.get("role", String.class)));
    }

    /** Generates a fresh opaque refresh token (returned to the client). */
    public String generateRefreshToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** SHA-256 hex of a refresh token — the form stored in the database. */
    public static String hashRefreshToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public long accessTtlSeconds() {
        return accessTtl.toSeconds();
    }
}
