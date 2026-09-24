package com.spotshare.auth;

import java.io.IOException;

import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.util.List;

/**
 * Reads the Bearer access token on each request and, when valid, places an
 * {@link AuthenticatedUser} in the security context. Invalid or missing tokens
 * simply leave the context empty — access decisions stay with the security
 * rules, which return the friendly 401 envelope.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenService tokens;

    public JwtAuthenticationFilter(JwtTokenService tokens) {
        this.tokens = tokens;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7).trim();
            if (!token.isEmpty()) {
                try {
                    AuthenticatedUser user = tokens.parseAccessToken(token);
                    var auth = new UsernamePasswordAuthenticationToken(
                            user, null,
                            List.of(new SimpleGrantedAuthority("ROLE_" + user.role().name())));
                    SecurityContextHolder.getContext().setAuthentication(auth);
                } catch (JwtException | IllegalArgumentException e) {
                    SecurityContextHolder.clearContext();
                }
            }
        }
        chain.doFilter(request, response);
    }
}
