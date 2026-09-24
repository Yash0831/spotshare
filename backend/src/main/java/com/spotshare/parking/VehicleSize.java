package com.spotshare.parking;

/**
 * Vehicle sizes a space can hold. Drivers filter by these in discovery
 * (Phase 4); hosts pick every size that fits their space.
 */
public enum VehicleSize {
    MOTORCYCLE,
    SEDAN,
    SUV,
    VAN,
    TRUCK
}
