package com.spotshare.photo;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ParkingPhotoRepository extends JpaRepository<ParkingPhoto, UUID> {

    List<ParkingPhoto> findBySpaceIdOrderBySortOrderAsc(UUID spaceId);
}
