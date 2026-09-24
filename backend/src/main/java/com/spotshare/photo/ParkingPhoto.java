package com.spotshare.photo;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Metadata for a photo of a parking space. The binary lives in S3-compatible
 * storage (see Phase 4); only the storage key is recorded here. Photos are
 * PUBLIC (visible in search results). Maps to the {@code parking_photos}
 * table owned by Flyway migration V1.
 */
@Entity
@Table(name = "parking_photos")
public class ParkingPhoto {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "space_id", nullable = false)
    private UUID spaceId;

    @Column(name = "storage_key", nullable = false, length = 512)
    private String storageKey;

    @Column(name = "content_type", length = 128)
    private String contentType;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected ParkingPhoto() {
        // JPA
    }

    public ParkingPhoto(UUID spaceId, String storageKey, String contentType, int sortOrder) {
        this.spaceId = spaceId;
        this.storageKey = storageKey;
        this.contentType = contentType;
        this.sortOrder = sortOrder;
        this.createdAt = OffsetDateTime.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getSpaceId() {
        return spaceId;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
