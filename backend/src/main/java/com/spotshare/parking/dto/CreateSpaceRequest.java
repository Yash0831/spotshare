package com.spotshare.parking.dto;

import java.util.List;

import com.spotshare.parking.ParkingType;
import com.spotshare.parking.VehicleSize;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * One-time host space setup (the 5-step wizard posts this). The authorization
 * confirmation is a hard requirement: {@code @AssertTrue} rejects creation
 * without it, and the service records the acceptance timestamp.
 */
public record CreateSpaceRequest(
        @NotBlank(message = "Give your space a short label, like a space number (e.g. B17).")
        @Size(max = 64, message = "The space label is too long (max 64 characters).")
        String label,

        @NotBlank(message = "Enter the street address of your parking space.")
        @Size(max = 255, message = "The address is too long (max 255 characters).")
        String address,

        @NotBlank(message = "Enter the city.")
        @Size(max = 128, message = "The city name is too long (max 128 characters).")
        String city,

        @NotBlank(message = "Enter the 2-letter state code, e.g. IL.")
        @Pattern(regexp = "(?i)^[a-z]{2}$", message = "Enter a 2-letter state code, e.g. IL.")
        String state,

        @NotBlank(message = "Enter the ZIP code.")
        @Pattern(regexp = "^\\d{5}(-\\d{4})?$",
                message = "Enter a valid ZIP code, e.g. 60601 or 60601-1234.")
        String zipCode,

        @NotNull(message = "Enter the latitude of your space.")
        @DecimalMin(value = "-90", message = "Latitude must be between -90 and 90.")
        @DecimalMax(value = "90", message = "Latitude must be between -90 and 90.")
        Double latitude,

        @NotNull(message = "Enter the longitude of your space.")
        @DecimalMin(value = "-180", message = "Longitude must be between -180 and 180.")
        @DecimalMax(value = "180", message = "Longitude must be between -180 and 180.")
        Double longitude,

        @NotBlank(message = "Enter the area or neighborhood name shown publicly, e.g. West Loop.")
        @Size(max = 128, message = "The area label is too long (max 128 characters).")
        String areaLabel,

        @NotNull(message = "Choose the type of parking space.")
        ParkingType parkingType,

        @Size(max = 2000, message = "The description is too long (max 2000 characters).")
        String description,

        @NotEmpty(message = "Select at least one vehicle size that fits your space.")
        List<@NotNull VehicleSize> vehicleSizes,

        @Positive(message = "The height limit must be a positive number of inches.")
        Integer heightLimitInches,

        boolean covered,

        boolean evCharging,

        @Size(max = 2000, message = "The parking instructions are too long (max 2000 characters).")
        String parkingInstructions,

        @AssertTrue(message = "Please confirm you own, control, or have permission "
                + "to share this parking space.")
        boolean authorizationConfirmed
) {
}
