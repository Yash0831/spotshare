package com.spotshare.auth;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import com.spotshare.auth.dto.LoginRequest;
import com.spotshare.auth.dto.RegisterRequest;
import com.spotshare.common.ApiException;
import com.spotshare.config.AppProperties;
import com.spotshare.user.Role;
import com.spotshare.user.User;
import com.spotshare.user.UserRepository;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Web-layer slice: Bean Validation, the JWT security chain, and the error
 * envelope — no database. Service logic is covered by {@link AuthServiceTest};
 * the database-backed flows live in {@link AuthIntegrationTest}.
 */
@WebMvcTest(AuthController.class)
@Import(SecurityConfig.class)
class AuthWebSliceTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private AuthService authService;

    @MockBean
    private UserRepository users;

    @MockBean
    private JwtAuthenticationFilter jwtFilter;

    @TestConfiguration
    static class TestConfig {
        // Real props, marked primary: the @EnableConfigurationProperties bean
        // from the app class also exists in the slice but isn't bound here.
        @Bean
        @Primary
        AppProperties appProperties() {
            return new AppProperties(
                    new AppProperties.Cors("http://localhost:5173"),
                    new AppProperties.Jwt("test-secret-that-is-long-enough-for-hs256!!", 15, 30));
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        SecurityContextHolder.clearContext();
        // By default the mocked JWT filter just passes the request through
        // (no authentication); individual tests override this as needed.
        doAnswer(inv -> {
            ((FilterChain) inv.getArgument(2))
                    .doFilter(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(jwtFilter).doFilter(any(), any(), any());
    }

    private String validRegistration() {
        return """
                {"email":"sam@example.com","password":"correct-horse-9",\
                "firstName":"Sam","lastName":"Reyes","phone":"+13125550100"}
                """;
    }

    @Test
    void register_validationFailure_returnsEnvelope() throws Exception {
        mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"bad\",\"password\":\"short\","
                                + "\"firstName\":\"\",\"lastName\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.correlationId").exists());
    }

    @Test
    void register_duplicateEmail_returns409() throws Exception {
        given(authService.register(any(RegisterRequest.class)))
                .willThrow(ApiException.conflict("EMAIL_TAKEN",
                        "An account with this email already exists."));

        mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validRegistration()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_TAKEN"));
    }

    @Test
    void login_invalidCredentials_returns401() throws Exception {
        given(authService.login(any(LoginRequest.class)))
                .willThrow(ApiException.unauthorized("INVALID_CREDENTIALS",
                        "Email or password is incorrect."));

        mvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"sam@example.com\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @Test
    void me_withoutToken_returnsUnauthorizedEnvelope() throws Exception {
        mvc.perform(get("/api/v1/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("Please log in to continue."));
    }

    @Test
    void me_withAuthentication_returnsProfile() throws Exception {
        // Simulate the JWT filter accepting the token.
        doAnswer(inv -> {
            var request = (HttpServletRequest) inv.getArgument(0);
            var response = (HttpServletResponse) inv.getArgument(1);
            var chain = (FilterChain) inv.getArgument(2);
            var auth = new UsernamePasswordAuthenticationToken(
                    new AuthenticatedUser(UUID.randomUUID(), "sam@example.com", Role.USER),
                    null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
            SecurityContextHolder.getContext().setAuthentication(auth);
            chain.doFilter(request, response);
            return null;
        }).when(jwtFilter).doFilter(any(), any(), any());

        given(users.findById(any(UUID.class))).willReturn(Optional.of(
                new User("sam@example.com", "hash", "Sam", "Reyes", null, Role.USER)));

        mvc.perform(get("/api/v1/me")
                        .header("Authorization", "Bearer test-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("sam@example.com"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }
}
