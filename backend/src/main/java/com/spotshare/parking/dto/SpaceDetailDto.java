package com.spotshare.parking.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.spotshare.availability.DisplayState;
import com.spotshare.parking.ParkingType;
import com.spotshare.parking.VehicleSize;

/**
 * The space owner's view. This is the ONLY space DTO in Phase 3, and it
 * includes the private fields (exact address, space label, parking
 * instructions) because the caller is the host. When discovery arrives
 * (Phase 4) it gets its own privacy-safe DTO that omits these fields —
 * privacy is enforced here in the backend DTOs, not just hidden in the UI.
 *
 * <p>{@code displayState} is derived at read time from the active flag and
 * the space's availability windows (see {@link DisplayState}) — it is never
 * stored.
 */
public record SpaceDetailDto(
        UUID id,
        UUID hostId,
        String label,
        String address,
        String city,
        String state,
        String zipCode,
        double latitude,
        double longitude,
        String areaLabel,
        ParkingType parkingType,
        String description,
        List<VehicleSize> vehicleSizes,
        Integer heightLimitInches,
        boolean covered,
        boolean evCharging,
        String parkingInstructions,
        boolean authorizationConfirmed,
        OffsetDateTime authorizationConfirmedAt,
        boolean active,
        List<PhotoDto> photos,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        DisplayState displayState
) {
}
