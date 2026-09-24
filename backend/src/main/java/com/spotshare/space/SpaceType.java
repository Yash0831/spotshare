package com.spotshare.space;

/**
 * The kind of private parking space being shared. Exactly these values;
 * the set is locked by the product spec (V1).
 */
public enum SpaceType {
    DRIVEWAY,
    GARAGE,
    PARKING_LOT,
    ASSIGNED_SPACE,
    EV_SPACE,
    OTHER_PRIVATE_SPACE
}
