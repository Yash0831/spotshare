package com.spotshare.availability;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence for {@link AvailabilityWindow}. */
public interface AvailabilityWindowRepository extends JpaRepository<AvailabilityWindow, UUID> {

    List<AvailabilityWindow> findBySpaceIdOrderByStartsAtAsc(UUID spaceId);

    List<AvailabilityWindow> findBySpaceIdAndEndsAtAfterOrderByStartsAtAsc(UUID spaceId,
                                                                           OffsetDateTime now);

    /** All upcoming/active windows across the host's spaces, soonest first. */
    List<AvailabilityWindow> findBySpace_Host_IdAndEndsAtAfterOrderByStartsAtAsc(UUID hostId,
                                                                                 OffsetDateTime now);

    /**
     * Windows for the space overlapping the half-open period
     * {@code [start, end)}. Adjacent windows (one ends exactly when the other
     * starts) do NOT overlap. Used to reject ambiguous double-shares.
     */
    @Query("select w from AvailabilityWindow w where w.space.id = :spaceId"
            + " and w.startsAt < :end and w.endsAt > :start")
    List<AvailabilityWindow> findOverlapping(@Param("spaceId") UUID spaceId,
                                             @Param("start") OffsetDateTime start,
                                             @Param("end") OffsetDateTime end);

    /**
     * Windows live right now ({@code startsAt <= now < endsAt}). Overlaps are
     * rejected at share time, so a space normally has at most one.
     */
    @Query("select w from AvailabilityWindow w where w.space.id = :spaceId"
            + " and w.startsAt <= :now and w.endsAt > :now order by w.startsAt asc")
    List<AvailabilityWindow> findLive(@Param("spaceId") UUID spaceId,
                                      @Param("now") OffsetDateTime now);
}
