package com.spotshare.parking;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import com.spotshare.auth.AuthenticatedUser;
import com.spotshare.auth.JwtAuthenticationFilter;
import com.spotshare.auth.SecurityConfig;
import com.spotshare.availability.DisplayState;
import com.spotshare.common.ApiException;
import com.spotshare.config.AppProperties;
import com.spotshare.parking.ParkingSpaceService.PhotoContent;
import com.spotshare.parking.dto.PhotoDto;
import com.spotshare.parking.dto.SpaceDetailDto;
import com.spotshare.user.Role;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Web-layer slice for spaces: Bean Validation, the JWT security chain, owner
 * authorization, and the error envelope — no database. Service logic is
 * covered by {@link ParkingSpaceServiceTest}; database-backed flows live in
 * {@link ParkingSpaceIntegrationTest}.
 */
@WebMvcTest(ParkingSpaceController.class)
@Import(SecurityConfig.class)
class ParkingSpaceWebSliceTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private ParkingSpaceService spaces;

    @MockBean
    private JwtAuthenticationFilter jwtFilter;

    @TestConfiguration
    static class TestConfig {
        @Bean
        @Primary
        AppProperties appProperties() {
            return new AppProperties(
                    new AppProperties.Cors("http://localhost:5173"),
                    new AppProperties.Jwt("test-secret-that-is-long-enough-for-hs256!!", 15, 30));
        }
    }

    private final UUID hostId = UUID.randomUUID();
    private final UUID spaceId = UUID.randomUUID();

    @BeforeEach
    void setUp() throws Exception {
        SecurityContextHolder.clearContext();
        doAnswer(inv -> {
            ((FilterChain) inv.getArgument(2))
                    .doFilter(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(jwtFilter).doFilter(any(), any(), any());
    }

    /** Simulates the JWT filter accepting the token for the host user. */
    private void authenticate() throws Exception {
        doAnswer(inv -> {
            var chain = (FilterChain) inv.getArgument(2);
            var auth = new UsernamePasswordAuthenticationToken(
                    new AuthenticatedUser(hostId, "host@example.com", Role.USER),
                    null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
            SecurityContextHolder.getContext().setAuthentication(auth);
            chain.doFilter(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(jwtFilter).doFilter(any(), any(), any());
    }

    private SpaceDetailDto detailDto(boolean active) {
        return new SpaceDetailDto(
                spaceId, hostId, "B17", "123 Wacker Dr", "Chicago", "IL", "60601",
                41.8858, -87.6189, "West Loop", ParkingType.ASSIGNED_SPACE,
                "Covered spot near the elevators.", List.of(VehicleSize.SEDAN), 84,
                true, false, "Gate code 1234, level 2.", true,
                OffsetDateTime.now(), active, List.of(),
                OffsetDateTime.now(), OffsetDateTime.now(),
                active ? DisplayState.PRIVATE : DisplayState.OFFLINE);
    }

    private String validCreateJson(boolean authorized) {
        return """
                {"label":"B17","address":"123 Wacker Dr","city":"Chicago","state":"IL",\
                "zipCode":"60601","latitude":41.8858,"longitude":-87.6189,\
                "areaLabel":"West Loop","parkingType":"ASSIGNED_SPACE",\
                "vehicleSizes":["SEDAN","SUV"],"heightLimitInches":84,"covered":true,\
                "evCharging":false,"parkingInstructions":"Gate code 1234, level 2.",\
                "authorizationConfirmed":%s}\
                """.formatted(authorized);
    }

    @Test
    void createSpace_withoutAuthorizationConfirmation_returns400() throws Exception {
        authenticate();

        mvc.perform(post("/api/v1/spaces")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCreateJson(false)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void createSpace_invalidBody_returns400() throws Exception {
        authenticate();

        mvc.perform(post("/api/v1/spaces")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.correlationId").exists());
    }

    @Test
    void createSpace_valid_returns201WithOwnerDto() throws Exception {
        authenticate();
        given(spaces.createSpace(eq(hostId), any())).willReturn(detailDto(true));

        mvc.perform(post("/api/v1/spaces")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCreateJson(true)))
                .andExpect(status().isCreated())
                // The owner DTO carries the private fields — privacy is
                // enforced by which DTO is returned, not by the UI.
                .andExpect(jsonPath("$.address").value("123 Wacker Dr"))
                .andExpect(jsonPath("$.parkingInstructions").value("Gate code 1234, level 2."))
                .andExpect(jsonPath("$.authorizationConfirmed").value(true));
    }

    @Test
    void mySpaces_withoutToken_returns401() throws Exception {
        mvc.perform(get("/api/v1/spaces/mine"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void getSpace_ofAnotherUser_returns403Envelope() throws Exception {
        authenticate();
        given(spaces.getSpace(eq(hostId), eq(spaceId)))
                .willThrow(ApiException.forbidden("NOT_YOUR_SPACE",
                        "This parking space belongs to another account."));

        mvc.perform(get("/api/v1/spaces/" + spaceId))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_YOUR_SPACE"))
                .andExpect(jsonPath("$.message").value("This parking space belongs to another account."));
    }

    @Test
    void deactivateSpace_returns200WithInactiveDto() throws Exception {
        authenticate();
        given(spaces.deactivateSpace(eq(hostId), eq(spaceId))).willReturn(detailDto(false));

        mvc.perform(delete("/api/v1/spaces/" + spaceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
    }

    @Test
    void uploadPhoto_valid_returns201() throws Exception {
        authenticate();
        UUID photoId = UUID.randomUUID();
        given(spaces.addPhoto(eq(hostId), eq(spaceId), any()))
                .willReturn(new PhotoDto(photoId, "image/jpeg", 0,
                        "/api/v1/spaces/" + spaceId + "/photos/" + photoId + "/content",
                        OffsetDateTime.now()));
        var file = new MockMultipartFile("photo", "p.jpg", "image/jpeg", new byte[] {1, 2, 3});

        mvc.perform(multipart("/api/v1/spaces/" + spaceId + "/photos").file(file))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.contentType").value("image/jpeg"));
    }

    @Test
    void photoContent_isPublic_noTokenRequired() throws Exception {
        // Photos carry no private location data, so the content endpoint is
        // public by design (discovery shows photos in Phase 4).
        UUID photoId = UUID.randomUUID();
        given(spaces.photoContent(eq(spaceId), eq(photoId)))
                .willReturn(new PhotoContent(new byte[] {1, 2, 3}, "image/jpeg"));

        mvc.perform(get("/api/v1/spaces/" + spaceId + "/photos/" + photoId + "/content"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/jpeg"))
                .andExpect(content().bytes(new byte[] {1, 2, 3}));
    }
}
