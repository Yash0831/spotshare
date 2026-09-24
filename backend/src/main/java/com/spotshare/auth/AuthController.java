package com.spotshare.auth;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.spotshare.auth.dto.AuthResponse;
import com.spotshare.auth.dto.LoginRequest;
import com.spotshare.auth.dto.RefreshRequest;
import com.spotshare.auth.dto.RegisterRequest;
import com.spotshare.auth.dto.UserDto;
import com.spotshare.common.ApiException;
import com.spotshare.user.UserRepository;

import jakarta.validation.Valid;

/**
 * Public auth endpoints plus the authenticated {@code /me} profile lookup.
 */
@RestController
@RequestMapping("/api/v1")
public class AuthController {

    private final AuthService authService;
    private final UserRepository users;

    public AuthController(AuthService authService, UserRepository users) {
        this.authService = authService;
        this.users = users;
    }

    @PostMapping("/auth/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(req));
    }

    @PostMapping("/auth/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest req) {
        return authService.login(req);
    }

    @PostMapping("/auth/refresh")
    public AuthResponse refresh(@Valid @RequestBody RefreshRequest req) {
        return authService.refresh(req.refreshToken());
    }

    @PostMapping("/auth/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshRequest req) {
        authService.logout(req.refreshToken());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public UserDto me(@AuthenticationPrincipal AuthenticatedUser principal) {
        return users.findById(principal.id())
                .map(AuthService::toDto)
                .orElseThrow(() -> ApiException.unauthorized("INVALID_TOKEN",
                        "Your session is no longer valid. Please log in again."));
    }
}
