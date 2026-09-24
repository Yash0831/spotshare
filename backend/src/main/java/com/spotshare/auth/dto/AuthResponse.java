package com.spotshare.auth.dto;

/** Token pair returned by register / login / refresh. */
public record AuthResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresIn,
        UserDto user
) {
    public static AuthResponse bearer(String accessToken, String refreshToken,
                                      long expiresInSeconds, UserDto user) {
        return new AuthResponse(accessToken, refreshToken, "Bearer", expiresInSeconds, user);
    }
}
