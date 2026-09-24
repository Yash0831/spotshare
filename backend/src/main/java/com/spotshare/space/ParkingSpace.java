package com.spotshare.space;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A host's parking listing. Maps to the {@code parking_spaces} table owned by
 * Flyway migration V1 (Hibernate validates, never creates).
 *
 * <p>Privacy: {@code address} and {@code instructions} are private and must
 * only be serialized into DTOs after a reservation is CONFIRMED, for the
 * booking driver and the host. Public views use {@code displayArea} plus
 * rounded coordinates instead.
 *
 * <p>A space may only be listed when the host confirmed they are authorized
 * to share it ({@code authorizationConfirmed} + {@code authorizationConfirmedAt}).
 */
@Entity
@Table(name = "parking_spaces")
public class ParkingSpace {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "host_id", nullable = false)
    private UUID hostId;

    /** Private — never exposed through public endpoints. */
    @Column(nullable = false)
    private String address;

    @Column(nullable = false)
    private String city;

    @Column(nullable = false, length = 50)
    private String state;

    @Column(name = "zip_code", nullable = false, length = 20)
    private String zipCode;

    @Column(nullable = false)
    private Double latitude;

    @Column(nullable = false)
    private Double longitude;

    /** Public street-area/neighborhood label shown in search results. */
    @Column(name = "display_area", nullable = false)
    private String displayArea;

    @Enumerated(EnumType.STRING)
    @Column(name = "space_type", nullable = false, length = 32)
    private SpaceType spaceType;

    @Column(columnDefinition = "text")
    private String description;

    @Column(name = "hourly_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal hourlyPrice;

    @Column(name = "max_vehicle_size", length = 100)
    private String maxVehicleSize;

    @Column(name = "height_restriction", length = 50)
    private String heightRestriction;

    /** Private — revealed only after a reservation is CONFIRMED. */
    @Column(columnDefinition = "text")
    private String instructions;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "authorization_confirmed", nullable = false)
    private boolean authorizationConfirmed;

    @Column(name = "authorization_confirmed_at")
    private OffsetDateTime authorizationConfirmedAt;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected ParkingSpace() {
        // JPA
    }

    public UUID getId() {
        return id;
    }

    public UUID getHostId() {
        return hostId;
    }

    public void setHostId(UUID hostId) {
        this.hostId = hostId;
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

    public Double getLatitude() {
        return latitude;
    }

    public void setLatitude(Double latitude) {
        this.latitude = latitude;
    }

    public Double getLongitude() {
        return longitude;
    }

    public void setLongitude(Double longitude) {
        this.longitude = longitude;
    }

    public String getDisplayArea() {
        return displayArea;
    }

    public void setDisplayArea(String displayArea) {
        this.displayArea = displayArea;
    }

    public SpaceType getSpaceType() {
        return spaceType;
    }

    public void setSpaceType(SpaceType spaceType) {
        this.spaceType = spaceType;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public BigDecimal getHourlyPrice() {
        return hourlyPrice;
    }

    public void setHourlyPrice(BigDecimal hourlyPrice) {
        this.hourlyPrice = hourlyPrice;
    }

    public String getMaxVehicleSize() {
        return maxVehicleSize;
    }

    public void setMaxVehicleSize(String maxVehicleSize) {
        this.maxVehicleSize = maxVehicleSize;
    }

    public String getHeightRestriction() {
        return heightRestriction;
    }

    public void setHeightRestriction(String heightRestriction) {
        this.heightRestriction = heightRestriction;
    }

    public String getInstructions() {
        return instructions;
    }

    public void setInstructions(String instructions) {
        this.instructions = instructions;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
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

    public Long getVersion() {
        return version;
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
