package com.spotshare.parking;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * A photo of a parking space, stored as bytes in the database (the simplest
 * honest V1 storage — no S3, no fake CDN URLs). Photos carry no private
 * location data, so the content endpoint is public by design (discovery
 * shows photos in Phase 4).
 *
 * <p>Maps to the {@code parking_photos} table owned by Flyway migration V2.
 */
@Entity
@Table(name = "parking_photos")
public class ParkingPhoto {

    @Id
    private UUID id = UUID.randomUUID();

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "space_id", nullable = false)
    private ParkingSpace space;

    /** Raw image bytes (validated on upload: JPEG/PNG/WebP, max 5 MB). */
    @Column(nullable = false)
    private byte[] data;

    @Column(name = "content_type", nullable = false, length = 64)
    private String contentType;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected ParkingPhoto() {
        // JPA
    }

    public ParkingPhoto(ParkingSpace space, byte[] data, String contentType, int sortOrder) {
        this.space = space;
        this.data = data;
        this.contentType = contentType;
        this.sortOrder = sortOrder;
        this.createdAt = OffsetDateTime.now();
    }

    public UUID getId() {
        return id;
    }

    public ParkingSpace getSpace() {
        return space;
    }

    public byte[] getData() {
        return data;
    }

    public String getContentType() {
        return contentType;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
