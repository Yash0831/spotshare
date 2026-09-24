package com.spotshare.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/** Login payload. Same message for unknown email and wrong password (no account enumeration). */
public record LoginRequest(
        @NotBlank(message = "Email is required.")
        @Email(message = "Enter a valid email address.")
        String email,

        @NotBlank(message = "Password is required.")
        String password
) {
}
