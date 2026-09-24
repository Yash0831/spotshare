package com.spotshare.availability;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * An explicit time window during which a parking space is bookable. A
 * reservation [arrival, departure) must be fully contained within ONE window.
 * No recurring schedules in V1. Maps to the {@code availability_windows}
 * table owned by Flyway migration V1.
 */
@Entity
@Table(name = "availability_windows")
public class AvailabilityWindow {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "space_id", nullable = false)
    private UUID spaceId;

    @Column(name = "starts_at", nullable = false)
    private OffsetDateTime startsAt;

    @Column(name = "ends_at", nullable = false)
    private OffsetDateTime endsAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected AvailabilityWindow() {
        // JPA
    }

    public AvailabilityWindow(UUID spaceId, OffsetDateTime startsAt, OffsetDateTime endsAt) {
        this.spaceId = spaceId;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.createdAt = OffsetDateTime.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getSpaceId() {
        return spaceId;
    }

    public OffsetDateTime getStartsAt() {
        return startsAt;
    }

    public void setStartsAt(OffsetDateTime startsAt) {
        this.startsAt = startsAt;
    }

    public OffsetDateTime getEndsAt() {
        return endsAt;
    }

    public void setEndsAt(OffsetDateTime endsAt) {
        this.endsAt = endsAt;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
