package com.spotshare.auth.dto;

import jakarta.validation.constraints.NotBlank;

/** Carries the opaque refresh token for rotation (/refresh) and revocation (/logout). */
public record RefreshRequest(
        @NotBlank(message = "Refresh token is required.")
        String refreshToken
) {
}
