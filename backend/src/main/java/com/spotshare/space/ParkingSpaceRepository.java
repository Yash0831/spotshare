package com.spotshare.space;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ParkingSpaceRepository extends JpaRepository<ParkingSpace, UUID> {

    List<ParkingSpace> findByHostId(UUID hostId);

    List<ParkingSpace> findByHostIdAndActive(UUID hostId, boolean active);

    List<ParkingSpace> findByActive(boolean active);
}
