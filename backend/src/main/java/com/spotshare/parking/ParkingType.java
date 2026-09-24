package com.spotshare.parking;

/**
 * The kind of private parking a host lists. These are the exact V1 space
 * types from the product spec — no public street parking, no fire lanes.
 */
public enum ParkingType {
    DRIVEWAY,
    PRIVATE_GARAGE,
    ASSIGNED_SPACE,
    PRIVATE_LOT,
    EV_SPACE,
    OTHER_PRIVATE
}
