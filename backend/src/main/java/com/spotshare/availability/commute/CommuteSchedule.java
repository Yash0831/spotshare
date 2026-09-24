package com.spotshare.availability.commute;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.UUID;

import com.spotshare.parking.ParkingSpace;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * One weekly commute entry for a space: "this spot is empty every Monday
 * 9:00–17:00". The materializer turns these rows into real
 * {@code availability_windows} (source=COMMUTE) for the next
 * {@link CommuteMaterializer#DAYS_AHEAD} days.
 *
 * <p>{@code dayOfWeek} is 0=Monday .. 6=Sunday. {@code startTime}/{@code endTime}
 * are local times-of-day in the {@code timezone} IANA zone (the host's device
 * timezone at setup) — never an instant, so the window lands at 9:00 local
 * even across daylight-saving changes. At most one entry per weekday per
 * space (unique key), which makes same-weekday overlaps impossible.
 *
 * <p>Maps to the {@code commute_schedules} table owned by Flyway migration V5
 * (Hibernate validates, never creates).
 */
@Entity
@Table(name = "commute_schedules")
public class CommuteSchedule {

    @Id
    // Assigned in Java so the id is stable from construction.
    private UUID id = UUID.randomUUID();

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "space_id", nullable = false)
    private ParkingSpace space;

    /** 0 = Monday, 6 = Sunday. */
    @Column(name = "day_of_week", nullable = false)
    private int dayOfWeek;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    /**
     * Hourly price in integer cents, or {@code null} for a free commute
     * window. Money is always integer cents — never floating point.
     */
    @Column(name = "hourly_rate_cents")
    private Integer hourlyRateCents;

    /** IANA zone name, e.g. "America/Chicago". */
    @Column(name = "timezone", nullable = false, length = 64)
    private String timezone;

    /** False while paused: no new windows are materialized. */
    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected CommuteSchedule() {
        // JPA
    }

    public CommuteSchedule(ParkingSpace space, int dayOfWeek, LocalTime startTime,
                           LocalTime endTime, Integer hourlyRateCents, String timezone,
                           OffsetDateTime now) {
        this.space = space;
        this.dayOfWeek = dayOfWeek;
        this.startTime = startTime;
        this.endTime = endTime;
        this.hourlyRateCents = hourlyRateCents;
        this.timezone = timezone;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public ParkingSpace getSpace() {
        return space;
    }

    public int getDayOfWeek() {
        return dayOfWeek;
    }

    public LocalTime getStartTime() {
        return startTime;
    }

    public LocalTime getEndTime() {
        return endTime;
    }

    public Integer getHourlyRateCents() {
        return hourlyRateCents;
    }

    public String getTimezone() {
        return timezone;
    }

    public boolean isActive() {
        return active;
    }

    /** Pause or resume. The caller updates {@code updatedAt}. */
    public void setActive(boolean active, OffsetDateTime now) {
        this.active = active;
        this.updatedAt = now;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
