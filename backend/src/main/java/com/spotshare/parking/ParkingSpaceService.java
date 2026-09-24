package com.spotshare.parking;

import java.io.IOException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.spotshare.availability.AvailabilityWindow;
import com.spotshare.availability.AvailabilityWindowRepository;
import com.spotshare.availability.DisplayState;
import com.spotshare.common.ApiException;
import com.spotshare.parking.dto.CreateSpaceRequest;
import com.spotshare.parking.dto.PhotoDto;
import com.spotshare.parking.dto.SpaceDetailDto;
import com.spotshare.parking.dto.UpdateSpaceRequest;
import com.spotshare.user.User;
import com.spotshare.user.UserRepository;

/**
 * Host parking-space lifecycle: one-time setup, edits, photos, and
 * soft-deactivation. Every space-scoped operation verifies the caller is the
 * space's host; a different authenticated user gets a 403, never the data.
 */
@Service
public class ParkingSpaceService {

    /** Photo upload limits — simple and honest V1 values. */
    public static final long MAX_PHOTO_BYTES = 5L * 1024 * 1024;
    public static final int MAX_PHOTOS_PER_SPACE = 8;
    private static final Set<String> ALLOWED_PHOTO_TYPES =
            Set.of("image/jpeg", "image/png", "image/webp");

    private final ParkingSpaceRepository spaces;
    private final ParkingPhotoRepository photos;
    private final UserRepository users;
    private final AvailabilityWindowRepository availability;
    private final Clock clock;

    public ParkingSpaceService(ParkingSpaceRepository spaces,
                               ParkingPhotoRepository photos,
                               UserRepository users,
                               AvailabilityWindowRepository availability,
                               Clock clock) {
        this.spaces = spaces;
        this.photos = photos;
        this.users = users;
        this.availability = availability;
        this.clock = clock;
    }

    @Transactional
    public SpaceDetailDto createSpace(UUID hostId, CreateSpaceRequest req) {
        User host = users.findById(hostId)
                .orElseThrow(() -> ApiException.unauthorized("INVALID_TOKEN",
                        "Your session is no longer valid. Please log in again."));
        // Belt and suspenders: @AssertTrue on the request rejects this at the
        // web layer, but the domain rule lives here too — a space can never
        // be created without a recorded authorization confirmation.
        if (!req.authorizationConfirmed()) {
            throw ApiException.unprocessable("AUTHORIZATION_REQUIRED",
                    "Please confirm you own, control, or have permission to share "
                    + "this parking space.");
        }
        ParkingSpace space = new ParkingSpace(host);
        applyFields(space, req);
        space.setAuthorizationConfirmed(true);
        space.setAuthorizationConfirmedAt(OffsetDateTime.now(clock));
        space.setActive(true);
        return toDetailDto(spaces.save(space));
    }

    @Transactional(readOnly = true)
    public List<SpaceDetailDto> mySpaces(UUID hostId) {
        return spaces.findByHostIdOrderByCreatedAtDesc(hostId).stream()
                .map(this::toDetailDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public SpaceDetailDto getSpace(UUID hostId, UUID spaceId) {
        return toDetailDto(ownedSpace(hostId, spaceId));
    }

    @Transactional
    public SpaceDetailDto updateSpace(UUID hostId, UUID spaceId, UpdateSpaceRequest req) {
        ParkingSpace space = ownedSpace(hostId, spaceId);
        applyFields(space, req);
        space.setUpdatedAt(OffsetDateTime.now(clock));
        return toDetailDto(spaces.save(space));
    }

    /**
     * Deactivation is soft and idempotent: DELETE flips {@code active} to
     * false; the record (and its photos) is retained. Calling it twice is a
     * no-op success.
     */
    @Transactional
    public SpaceDetailDto deactivateSpace(UUID hostId, UUID spaceId) {
        ParkingSpace space = ownedSpace(hostId, spaceId);
        space.setActive(false);
        space.setUpdatedAt(OffsetDateTime.now(clock));
        return toDetailDto(spaces.save(space));
    }

    @Transactional
    public PhotoDto addPhoto(UUID hostId, UUID spaceId, MultipartFile file) {
        ParkingSpace space = ownedSpace(hostId, spaceId);
        if (file == null || file.isEmpty()) {
            throw ApiException.unprocessable("INVALID_PHOTO",
                    "Choose a photo to upload.");
        }
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_PHOTO_TYPES.contains(contentType.toLowerCase())) {
            throw ApiException.unprocessable("INVALID_PHOTO",
                    "That file isn't a supported photo. Please use a JPEG, PNG, or WebP image.");
        }
        if (file.getSize() > MAX_PHOTO_BYTES) {
            throw ApiException.payloadTooLarge("PHOTO_TOO_LARGE",
                    "That photo is too large. Please use a photo under 5 MB.");
        }
        if (photos.countBySpaceId(spaceId) >= MAX_PHOTOS_PER_SPACE) {
            throw ApiException.unprocessable("PHOTO_LIMIT_REACHED",
                    "A space can have up to 8 photos. Remove one to add another.");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw ApiException.unprocessable("INVALID_PHOTO",
                    "We couldn't read that photo. Please try a different one.");
        }
        ParkingPhoto photo = new ParkingPhoto(space, bytes, contentType.toLowerCase(),
                photos.maxSortOrder(spaceId) + 1);
        photo = photos.save(photo);
        return toPhotoDto(spaceId, photo);
    }

    @Transactional
    public void deletePhoto(UUID hostId, UUID spaceId, UUID photoId) {
        ownedSpace(hostId, spaceId);
        ParkingPhoto photo = photos.findByIdAndSpaceId(photoId, spaceId)
                .orElseThrow(() -> ApiException.notFound("PHOTO_NOT_FOUND",
                        "We couldn't find that photo."));
        photos.delete(photo);
    }

    /** Public by design: photos carry no private location data. */
    @Transactional(readOnly = true)
    public PhotoContent photoContent(UUID spaceId, UUID photoId) {
        ParkingPhoto photo = photos.findByIdAndSpaceId(photoId, spaceId)
                .orElseThrow(() -> ApiException.notFound("PHOTO_NOT_FOUND",
                        "We couldn't find that photo."));
        return new PhotoContent(photo.getData(), photo.getContentType());
    }

    /** Raw bytes served by the public photo-content endpoint. */
    public record PhotoContent(byte[] data, String contentType) {
    }

    /** 404 when the space doesn't exist; 403 when it belongs to someone else. */
    private ParkingSpace ownedSpace(UUID hostId, UUID spaceId) {
        ParkingSpace space = spaces.findById(spaceId)
                .orElseThrow(() -> ApiException.notFound("SPACE_NOT_FOUND",
                        "We couldn't find that parking space."));
        if (!space.getHost().getId().equals(hostId)) {
            throw ApiException.forbidden("NOT_YOUR_SPACE",
                    "This parking space belongs to another account.");
        }
        return space;
    }

    private void applyFields(ParkingSpace space, CreateSpaceRequest req) {
        space.setLabel(req.label().trim());
        space.setAddress(req.address().trim());
        space.setCity(req.city().trim());
        space.setState(req.state().trim().toUpperCase());
        space.setZipCode(req.zipCode().trim());
        space.setLatitude(req.latitude());
        space.setLongitude(req.longitude());
        space.setAreaLabel(req.areaLabel().trim());
        space.setParkingType(req.parkingType());
        space.setDescription(blankToNull(req.description()));
        space.setVehicleSizes(req.vehicleSizes().stream()
                .map(Enum::name)
                .toArray(String[]::new));
        space.setHeightLimitInches(req.heightLimitInches());
        space.setCovered(req.covered());
        space.setEvCharging(req.evCharging());
        space.setParkingInstructions(blankToNull(req.parkingInstructions()));
    }

    private void applyFields(ParkingSpace space, UpdateSpaceRequest req) {
        applyFields(space, new CreateSpaceRequest(
                req.label(), req.address(), req.city(), req.state(), req.zipCode(),
                req.latitude(), req.longitude(), req.areaLabel(), req.parkingType(),
                req.description(), req.vehicleSizes(), req.heightLimitInches(),
                req.covered(), req.evCharging(), req.parkingInstructions(), true));
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private SpaceDetailDto toDetailDto(ParkingSpace space) {
        UUID spaceId = space.getId();
        List<PhotoDto> photoDtos = photos.findBySpaceIdOrderBySortOrderAsc(spaceId).stream()
                .map(photo -> toPhotoDto(spaceId, photo))
                .toList();
        List<VehicleSize> sizes = Arrays.stream(space.getVehicleSizes())
                .map(VehicleSize::valueOf)
                .collect(Collectors.toList());
        OffsetDateTime now = OffsetDateTime.now(clock);
        return new SpaceDetailDto(
                spaceId,
                space.getHost().getId(),
                space.getLabel(),
                space.getAddress(),
                space.getCity(),
                space.getState(),
                space.getZipCode(),
                space.getLatitude(),
                space.getLongitude(),
                space.getAreaLabel(),
                space.getParkingType(),
                space.getDescription(),
                sizes,
                space.getHeightLimitInches(),
                space.isCovered(),
                space.isEvCharging(),
                space.getParkingInstructions(),
                space.isAuthorizationConfirmed(),
                space.getAuthorizationConfirmedAt(),
                space.isActive(),
                photoDtos,
                space.getCreatedAt(),
                space.getUpdatedAt(),
                displayState(space, now));
    }

    /**
     * The user-facing state, derived at read time (never stored). In Phase 3
     * a live window means AVAILABLE (no reservations exist yet); RESERVED
     * arrives with Phase 5.
     */
    private DisplayState displayState(ParkingSpace space, OffsetDateTime now) {
        if (!space.isActive()) {
            return DisplayState.OFFLINE;
        }
        List<AvailabilityWindow> live = availability.findLive(space.getId(), now);
        if (live.isEmpty()) {
            return DisplayState.PRIVATE;
        }
        // Overlaps are rejected at share time, so a space has at most one
        // live window.
        AvailabilityWindow current = live.get(0);
        if (!current.getEndsAt().minusMinutes(30).isAfter(now)) {
            return DisplayState.RETURNING;
        }
        return DisplayState.AVAILABLE;
    }

    private PhotoDto toPhotoDto(UUID spaceId, ParkingPhoto photo) {
        return new PhotoDto(
                photo.getId(),
                photo.getContentType(),
                photo.getSortOrder(),
                "/api/v1/spaces/" + spaceId + "/photos/" + photo.getId() + "/content",
                photo.getCreatedAt());
    }
}
