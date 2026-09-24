package com.spotshare.reservation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.Formula;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A driver's booking of a parking space for [arrival, departure). Maps to the
 * {@code reservations} table owned by Flyway migration V1 (Hibernate
 * validates, never creates).
 *
 * <p>Double booking is impossible at the database level: the migration defines
 * {@code reserved_period} as a generated half-open {@code tstzrange} and adds
 * an {@code EXCLUDE ... WHERE (status = 'CONFIRMED')} constraint rejecting
 * overlapping periods on the same space.
 *
 * <p>{@code reservedPeriod} is mapped read-only via a formula that renders the
 * generated range column as text. It is derived by the database and never
 * written by the application.
 */
@Entity
@Table(name = "reservations")
public class Reservation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "space_id", nullable = false)
    private UUID spaceId;

    @Column(name = "driver_id", nullable = false)
    private UUID driverId;

    @Column(nullable = false)
    private OffsetDateTime arrival;

    @Column(nullable = false)
    private OffsetDateTime departure;

    /**
     * Read-only view of the DB-generated {@code reserved_period} tstzrange,
     * rendered as text (e.g. {@code ["2026-09-23 10:00:00+00","2026-09-23 12:00:00+00")}).
     */
    @Formula("reserved_period::text")
    private String reservedPeriod;

    @Column(name = "total_price", precision = 10, scale = 2)
    private BigDecimal totalPrice;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ReservationStatus status = ReservationStatus.CONFIRMED;

    @Column(name = "cancelled_by")
    private UUID cancelledBy;

    @Column(name = "cancelled_at")
    private OffsetDateTime cancelledAt;

    @Column(name = "cancel_reason", columnDefinition = "text")
    private String cancelReason;

    /**
     * Placeholder for the future payment phase. V1 reservations confirm
     * without any payment; this stays {@code NOT_REQUIRED} and is never
     * processed.
     */
    @Column(name = "payment_status", nullable = false, length = 32)
    private String paymentStatus = "NOT_REQUIRED";

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected Reservation() {
        // JPA
    }

    public Reservation(UUID spaceId, UUID driverId, OffsetDateTime arrival,
                       OffsetDateTime departure, BigDecimal totalPrice) {
        this.spaceId = spaceId;
        this.driverId = driverId;
        this.arrival = arrival;
        this.departure = departure;
        this.totalPrice = totalPrice;
        this.createdAt = OffsetDateTime.now();
        this.updatedAt = this.createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getSpaceId() {
        return spaceId;
    }

    public UUID getDriverId() {
        return driverId;
    }

    public OffsetDateTime getArrival() {
        return arrival;
    }

    public void setArrival(OffsetDateTime arrival) {
        this.arrival = arrival;
    }

    public OffsetDateTime getDeparture() {
        return departure;
    }

    public void setDeparture(OffsetDateTime departure) {
        this.departure = departure;
    }

    public String getReservedPeriod() {
        return reservedPeriod;
    }

    public BigDecimal getTotalPrice() {
        return totalPrice;
    }

    public void setTotalPrice(BigDecimal totalPrice) {
        this.totalPrice = totalPrice;
    }

    public ReservationStatus getStatus() {
        return status;
    }

    public void setStatus(ReservationStatus status) {
        this.status = status;
    }

    public UUID getCancelledBy() {
        return cancelledBy;
    }

    public void setCancelledBy(UUID cancelledBy) {
        this.cancelledBy = cancelledBy;
    }

    public OffsetDateTime getCancelledAt() {
        return cancelledAt;
    }

    public void setCancelledAt(OffsetDateTime cancelledAt) {
        this.cancelledAt = cancelledAt;
    }

    public String getCancelReason() {
        return cancelReason;
    }

    public void setCancelReason(String cancelReason) {
        this.cancelReason = cancelReason;
    }

    public String getPaymentStatus() {
        return paymentStatus;
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
