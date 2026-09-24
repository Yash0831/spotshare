package com.spotshare.parking;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Persistence for {@link ParkingPhoto}. */
public interface ParkingPhotoRepository extends JpaRepository<ParkingPhoto, UUID> {

    List<ParkingPhoto> findBySpaceIdOrderBySortOrderAsc(UUID spaceId);

    Optional<ParkingPhoto> findByIdAndSpaceId(UUID id, UUID spaceId);

    long countBySpaceId(UUID spaceId);

    @Query("select coalesce(max(p.sortOrder), -1) from ParkingPhoto p where p.space.id = :spaceId")
    int maxSortOrder(UUID spaceId);
}
