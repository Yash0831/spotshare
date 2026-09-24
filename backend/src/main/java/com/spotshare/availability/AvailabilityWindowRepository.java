package com.spotshare.availability;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface AvailabilityWindowRepository extends JpaRepository<AvailabilityWindow, UUID> {

    List<AvailabilityWindow> findBySpaceIdOrderByStartsAtAsc(UUID spaceId);

    List<AvailabilityWindow> findBySpaceIdAndStartsAtLessThanEqualAndEndsAtGreaterThanEqual(
            UUID spaceId, OffsetDateTime startsAtUpper, OffsetDateTime endsAtLower);
}
