package com.spotshare.parking;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;

import com.spotshare.common.ApiException;
import com.spotshare.parking.dto.CreateSpaceRequest;
import com.spotshare.parking.dto.SpaceDetailDto;
import com.spotshare.parking.dto.UpdateSpaceRequest;
import com.spotshare.user.Role;
import com.spotshare.user.User;
import com.spotshare.user.UserRepository;

/**
 * ParkingSpaceService behavior without a database: authorization confirmation,
 * owner-only access, deactivation semantics, and photo upload validation.
 * The database-backed workflow lives in {@link ParkingSpaceIntegrationTest}.
 */
@ExtendWith(MockitoExtension.class)
class ParkingSpaceServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-24T12:00:00Z");

    @Mock
    private ParkingSpaceRepository spaces;

    @Mock
    private ParkingPhotoRepository photos;

    @Mock
    private UserRepository users;

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private ParkingSpaceService service;

    private User host;
    private User stranger;

    @BeforeEach
    void setUp() {
        service = new ParkingSpaceService(spaces, photos, users, clock);
        host = new User("host@example.com", "hash", "Holly", "Host", null, Role.USER);
        stranger = new User("stranger@example.com", "hash", "Sam", "Stranger", null, Role.USER);
    }

    /** Stub the lookups a successful space mutation needs (strict stubs: each
     * test declares what it uses). */
    private void stubSuccessfulSave() {
        given(users.findById(host.getId())).willReturn(Optional.of(host));
        given(spaces.save(any(ParkingSpace.class)))
                .willAnswer((Answer<ParkingSpace>) inv -> inv.getArgument(0));
    }

    private CreateSpaceRequest validRequest(boolean authorized) {
        return new CreateSpaceRequest(
                "B17",
                "123 Wacker Dr",
                "Chicago",
                "il",
                "60601",
                41.8858,
                -87.6189,
                "West Loop",
                ParkingType.ASSIGNED_SPACE,
                "Covered spot near the elevators.",
                List.of(VehicleSize.SEDAN, VehicleSize.SUV),
                84,
                true,
                false,
                "Gate code 1234, level 2.",
                authorized);
    }

    private ParkingSpace existingSpace(User owner) {
        ParkingSpace space = new ParkingSpace(owner);
        space.setLabel("B17");
        space.setAddress("123 Wacker Dr");
        space.setCity("Chicago");
        space.setState("IL");
        space.setZipCode("60601");
        space.setLatitude(41.8858);
        space.setLongitude(-87.6189);
        space.setAreaLabel("West Loop");
        space.setParkingType(ParkingType.ASSIGNED_SPACE);
        space.setVehicleSizes(new String[] { "SEDAN" });
        return space;
    }

    private static void assertApiException(ApiException ex, HttpStatus status, String code) {
        assertThat(ex.getStatus()).isEqualTo(status);
        assertThat(ex.getCode()).isEqualTo(code);
    }

    @Test
    void createSpace_recordsAuthorizationConfirmationWithTimestamp() {
        stubSuccessfulSave();

        SpaceDetailDto dto = service.createSpace(host.getId(), validRequest(true));

        assertThat(dto.address()).isEqualTo("123 Wacker Dr");
        assertThat(dto.state()).isEqualTo("IL"); // normalized to uppercase
        assertThat(dto.authorizationConfirmed()).isTrue();
        assertThat(dto.authorizationConfirmedAt()).isEqualTo(NOW.atOffset(ZoneOffset.UTC));
        assertThat(dto.active()).isTrue();
        assertThat(dto.vehicleSizes()).containsExactly(VehicleSize.SEDAN, VehicleSize.SUV);
    }

    @Test
    void createSpace_withoutAuthorizationConfirmation_isRejected() {
        given(users.findById(host.getId())).willReturn(Optional.of(host));

        assertThatThrownBy(() -> service.createSpace(host.getId(), validRequest(false)))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertApiException((ApiException) ex,
                        HttpStatus.UNPROCESSABLE_ENTITY, "AUTHORIZATION_REQUIRED"));
    }

    @Test
    void getSpace_belongingToAnotherUser_isForbidden() {
        ParkingSpace space = existingSpace(stranger);
        given(spaces.findById(space.getId())).willReturn(Optional.of(space));

        assertThatThrownBy(() -> service.getSpace(host.getId(), space.getId()))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertApiException((ApiException) ex,
                        HttpStatus.FORBIDDEN, "NOT_YOUR_SPACE"));
    }

    @Test
    void getSpace_missing_isNotFound() {
        UUID id = UUID.randomUUID();
        given(spaces.findById(id)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.getSpace(host.getId(), id))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertApiException((ApiException) ex,
                        HttpStatus.NOT_FOUND, "SPACE_NOT_FOUND"));
    }

    @Test
    void updateSpace_byNonOwner_isForbidden() {
        ParkingSpace space = existingSpace(stranger);
        given(spaces.findById(space.getId())).willReturn(Optional.of(space));
        UpdateSpaceRequest req = new UpdateSpaceRequest(
                "B17", "123 Wacker Dr", "Chicago", "IL", "60601", 41.8858, -87.6189,
                "West Loop", ParkingType.ASSIGNED_SPACE, null, List.of(VehicleSize.SEDAN),
                null, false, false, null);

        assertThatThrownBy(() -> service.updateSpace(host.getId(), space.getId(), req))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertApiException((ApiException) ex,
                        HttpStatus.FORBIDDEN, "NOT_YOUR_SPACE"));
    }

    @Test
    void deactivateSpace_flipsActiveFlagAndIsIdempotent() {
        given(spaces.save(any(ParkingSpace.class)))
                .willAnswer((Answer<ParkingSpace>) inv -> inv.getArgument(0));
        ParkingSpace space = existingSpace(host);
        given(spaces.findById(space.getId())).willReturn(Optional.of(space));

        SpaceDetailDto first = service.deactivateSpace(host.getId(), space.getId());
        assertThat(first.active()).isFalse();

        // The record is retained (soft delete), so deactivating again succeeds.
        SpaceDetailDto second = service.deactivateSpace(host.getId(), space.getId());
        assertThat(second.active()).isFalse();
        // Never hard-deleted: the repository delete is never invoked.
        verify(spaces, never()).delete(any());
    }

    @Test
    void addPhoto_unsupportedType_isRejected() {
        ParkingSpace space = existingSpace(host);
        given(spaces.findById(space.getId())).willReturn(Optional.of(space));
        var file = new MockMultipartFile("photo", "anim.gif", "image/gif", new byte[] {1, 2, 3});

        assertThatThrownBy(() -> service.addPhoto(host.getId(), space.getId(), file))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertApiException((ApiException) ex,
                        HttpStatus.UNPROCESSABLE_ENTITY, "INVALID_PHOTO"));
    }

    @Test
    void addPhoto_tooLarge_isRejected() {
        ParkingSpace space = existingSpace(host);
        given(spaces.findById(space.getId())).willReturn(Optional.of(space));
        var file = new MockMultipartFile("photo", "big.jpg", "image/jpeg",
                new byte[(int) ParkingSpaceService.MAX_PHOTO_BYTES + 1]);

        assertThatThrownBy(() -> service.addPhoto(host.getId(), space.getId(), file))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertApiException((ApiException) ex,
                        HttpStatus.PAYLOAD_TOO_LARGE, "PHOTO_TOO_LARGE"));
    }

    @Test
    void addPhoto_beyondLimit_isRejected() {
        ParkingSpace space = existingSpace(host);
        given(spaces.findById(space.getId())).willReturn(Optional.of(space));
        given(photos.countBySpaceId(space.getId()))
                .willReturn((long) ParkingSpaceService.MAX_PHOTOS_PER_SPACE);
        var file = new MockMultipartFile("photo", "p.jpg", "image/jpeg", new byte[] {1, 2, 3});

        assertThatThrownBy(() -> service.addPhoto(host.getId(), space.getId(), file))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertApiException((ApiException) ex,
                        HttpStatus.UNPROCESSABLE_ENTITY, "PHOTO_LIMIT_REACHED"));
    }

    @Test
    void addPhoto_valid_assignsSortOrder() {
        ParkingSpace space = existingSpace(host);
        given(spaces.findById(space.getId())).willReturn(Optional.of(space));
        given(photos.countBySpaceId(space.getId())).willReturn(2L);
        given(photos.maxSortOrder(space.getId())).willReturn(1);
        given(photos.save(any(ParkingPhoto.class)))
                .willAnswer((Answer<ParkingPhoto>) inv -> inv.getArgument(0));
        var file = new MockMultipartFile("photo", "p.jpg", "image/jpeg", new byte[] {1, 2, 3});

        var dto = service.addPhoto(host.getId(), space.getId(), file);

        assertThat(dto.contentType()).isEqualTo("image/jpeg");
        assertThat(dto.sortOrder()).isEqualTo(2);
        assertThat(dto.contentUrl()).contains(space.getId().toString());
    }
}
