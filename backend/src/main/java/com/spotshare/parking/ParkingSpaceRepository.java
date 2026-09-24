package com.spotshare.parking;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@link ParkingSpace}. */
public interface ParkingSpaceRepository extends JpaRepository<ParkingSpace, UUID> {

    List<ParkingSpace> findByHostIdOrderByCreatedAtDesc(UUID hostId);

    Optional<ParkingSpace> findByIdAndHostId(UUID id, UUID hostId);
}
