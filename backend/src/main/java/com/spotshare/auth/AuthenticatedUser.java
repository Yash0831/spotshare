package com.spotshare.auth;

import java.util.UUID;

import com.spotshare.user.Role;

/**
 * The authenticated principal placed in the Spring Security context by
 * {@link JwtAuthenticationFilter}. Carries only what authorization checks need.
 */
public record AuthenticatedUser(UUID id, String email, Role role) {
}
