package com.spotshare.auth.dto;

import java.util.UUID;

import com.spotshare.user.Role;

/**
 * Public user shape. Never includes the password hash or internal timestamps
 * beyond what the UI needs.
 */
public record UserDto(
        UUID id,
        String email,
        String firstName,
        String lastName,
        String phone,
        Role role
) {
}
