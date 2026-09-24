package com.spotshare.reservation;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ReservationRepository extends JpaRepository<Reservation, UUID> {

    List<Reservation> findBySpaceId(UUID spaceId);

    List<Reservation> findByDriverId(UUID driverId);

    List<Reservation> findBySpaceIdAndStatus(UUID spaceId, ReservationStatus status);

    List<Reservation> findByDriverIdAndStatus(UUID driverId, ReservationStatus status);
}
