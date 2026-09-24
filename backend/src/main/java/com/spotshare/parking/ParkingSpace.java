package com.spotshare.parking;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import com.spotshare.user.User;

/**
 * A host's parking space, created once via the 5-step setup wizard and reused
 * for every "I'm leaving" share. The exact address and parking instructions
 * are private: they leave the backend only in the owner DTO (this phase) and
 * in the authorized reservation detail (Phase 5) — never in public DTOs.
 *
 * <p>Maps to the {@code parking_spaces} table owned by Flyway migration V2
 * (Hibernate validates, never creates). The {@code geom} geography column is
 * intentionally unmapped: the database generates and indexes it from lat/lng,
 * and geographic search (Phase 4) reads it in SQL.
 */
@Entity
@Table(name = "parking_spaces")
public class ParkingSpace {

    @Id
    // Assigned in Java so the id is stable from construction, like User.
    private UUID id = UUID.randomUUID();

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "host_id", nullable = false)
    private User host;

    /** Space number or short label, e.g. "B17". Private. */
    @Column(nullable = false, length = 64)
    private String label;

    /** Exact street address. Private — owner/reservation-driver only. */
    @Column(nullable = false, length = 255)
    private String address;

    @Column(nullable = false, length = 128)
    private String city;

    /** US state, 2 uppercase letters. */
    @Column(nullable = false, length = 2)
    private String state;

    @Column(name = "zip_code", nullable = false, length = 10)
    private String zipCode;

    @Column(nullable = false)
    private double latitude;

    @Column(nullable = false)
    private double longitude;

    /** Public neighborhood/area label shown in discovery, e.g. "West Loop". */
    @Column(name = "area_label", nullable = false, length = 128)
    private String areaLabel;

    @Enumerated(EnumType.STRING)
    @Column(name = "parking_type", nullable = false, length = 32)
    private ParkingType parkingType;

    @Column(length = 2000)
    private String description;

    /** Vehicle sizes that fit; stored as a Postgres text[] of enum names. */
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "vehicle_sizes", nullable = false, columnDefinition = "text[]")
    private String[] vehicleSizes = new String[0];

    /** Optional clearance height in inches, e.g. for garage spaces. */
    @Column(name = "height_limit_inches")
    private Integer heightLimitInches;

    @Column(nullable = false)
    private boolean covered;

    @Column(name = "ev_charging", nullable = false)
    private boolean evCharging;

    /** Private parking/access instructions — same privacy as the address. */
    @Column(name = "parking_instructions", length = 2000)
    private String parkingInstructions;

    /**
     * The host's authorization confirmation: "I confirm that I own, control,
     * or have permission to share this parking space." Creation is rejected
     * unless this is accepted; the timestamp is stored with the record.
     */
    @Column(name = "authorization_confirmed", nullable = false)
    private boolean authorizationConfirmed;

    @Column(name = "authorization_confirmed_at")
    private OffsetDateTime authorizationConfirmedAt;

    /** Soft-deactivation flag: DELETE sets this false; the row is retained. */
    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected ParkingSpace() {
        // JPA
    }

    public ParkingSpace(User host) {
        this.host = host;
        this.createdAt = OffsetDateTime.now();
        this.updatedAt = OffsetDateTime.now();
    }

    public UUID getId() {
        return id;
    }

    public User getHost() {
        return host;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getAddress() {
        return address;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getZipCode() {
        return zipCode;
    }

    public void setZipCode(String zipCode) {
        this.zipCode = zipCode;
    }

    public double getLatitude() {
        return latitude;
    }

    public void setLatitude(double latitude) {
        this.latitude = latitude;
    }

    public double getLongitude() {
        return longitude;
    }

    public void setLongitude(double longitude) {
        this.longitude = longitude;
    }

    public String getAreaLabel() {
        return areaLabel;
    }

    public void setAreaLabel(String areaLabel) {
        this.areaLabel = areaLabel;
    }

    public ParkingType getParkingType() {
        return parkingType;
    }

    public void setParkingType(ParkingType parkingType) {
        this.parkingType = parkingType;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String[] getVehicleSizes() {
        return vehicleSizes;
    }

    public void setVehicleSizes(String[] vehicleSizes) {
        this.vehicleSizes = vehicleSizes;
    }

    public Integer getHeightLimitInches() {
        return heightLimitInches;
    }

    public void setHeightLimitInches(Integer heightLimitInches) {
        this.heightLimitInches = heightLimitInches;
    }

    public boolean isCovered() {
        return covered;
    }

    public void setCovered(boolean covered) {
        this.covered = covered;
    }

    public boolean isEvCharging() {
        return evCharging;
    }

    public void setEvCharging(boolean evCharging) {
        this.evCharging = evCharging;
    }

    public String getParkingInstructions() {
        return parkingInstructions;
    }

    public void setParkingInstructions(String parkingInstructions) {
        this.parkingInstructions = parkingInstructions;
    }

    public boolean isAuthorizationConfirmed() {
        return authorizationConfirmed;
    }

    public void setAuthorizationConfirmed(boolean authorizationConfirmed) {
        this.authorizationConfirmed = authorizationConfirmed;
    }

    public OffsetDateTime getAuthorizationConfirmedAt() {
        return authorizationConfirmedAt;
    }

    public void setAuthorizationConfirmedAt(OffsetDateTime authorizationConfirmedAt) {
        this.authorizationConfirmedAt = authorizationConfirmedAt;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
