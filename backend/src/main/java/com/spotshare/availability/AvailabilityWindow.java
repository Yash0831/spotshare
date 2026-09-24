package com.spotshare.availability;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import com.spotshare.parking.ParkingSpace;

/**
 * One temporary share of a parking space: the host's "I'm leaving" window.
 * The window covers the half-open period {@code [startsAt, endsAt)} — the
 * listing is live while {@code startsAt <= now < endsAt}. Expiry is derived
 * from these timestamps, never stored, so there is nothing to "close" and no
 * scheduler to run (see {@link AvailabilityService}).
 *
 * <p>{@code hourlyRateCents} is {@code null} for a free share. Money is
 * always integer cents — never floating point.
 *
 * <p>Maps to the {@code availability_windows} table owned by Flyway
 * migration V3 (Hibernate validates, never creates).
 */
@Entity
@Table(name = "availability_windows")
public class AvailabilityWindow {

    @Id
    // Assigned in Java so the id is stable from construction.
    private UUID id = UUID.randomUUID();

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "space_id", nullable = false)
    private ParkingSpace space;

    /** Window start. For a manual share this is the moment the host shares. */
    @Column(name = "starts_at", nullable = false)
    private OffsetDateTime startsAt;

    /** The host's return time. The window is live while {@code now < endsAt}. */
    @Column(name = "ends_at", nullable = false)
    private OffsetDateTime endsAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private WindowSource source;

    /**
     * Hourly price in integer cents, or {@code null} for a free share.
     * Reservations (Phase 5) snapshot this value at booking time.
     */
    @Column(name = "hourly_rate_cents")
    private Integer hourlyRateCents;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected AvailabilityWindow() {
        // JPA
    }

    public AvailabilityWindow(ParkingSpace space, OffsetDateTime startsAt,
                              OffsetDateTime endsAt, WindowSource source,
                              Integer hourlyRateCents, OffsetDateTime createdAt) {
        this.space = space;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.source = source;
        this.hourlyRateCents = hourlyRateCents;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public ParkingSpace getSpace() {
        return space;
    }

    public OffsetDateTime getStartsAt() {
        return startsAt;
    }

    public OffsetDateTime getEndsAt() {
        return endsAt;
    }

    public WindowSource getSource() {
        return source;
    }

    public Integer getHourlyRateCents() {
        return hourlyRateCents;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    /** Live while {@code startsAt <= now < endsAt} — derived, never stored. */
    public boolean isLive(OffsetDateTime now) {
        return !now.isBefore(startsAt) && now.isBefore(endsAt);
    }

    /** True once the window has started and can no longer be removed. */
    public boolean hasStarted(OffsetDateTime now) {
        return !now.isBefore(startsAt);
    }
}
