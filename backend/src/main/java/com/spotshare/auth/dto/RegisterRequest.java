package com.spotshare.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Registration payload. Phone is optional; everything else is required.
 * Passwords are 8–72 chars (72 is bcrypt's input limit).
 */
public record RegisterRequest(
        @NotBlank(message = "Email is required.")
        @Email(message = "Enter a valid email address.")
        @Size(max = 255, message = "Email is too long.")
        String email,

        @NotBlank(message = "Password is required.")
        @Size(min = 8, max = 72, message = "Password must be 8–72 characters.")
        String password,

        @NotBlank(message = "First name is required.")
        @Size(max = 100, message = "First name is too long.")
        String firstName,

        @NotBlank(message = "Last name is required.")
        @Size(max = 100, message = "Last name is too long.")
        String lastName,

        @Pattern(regexp = "^[+()\\-\\d\\s]{7,32}$",
                message = "Enter a valid phone number.")
        String phone
) {
}
