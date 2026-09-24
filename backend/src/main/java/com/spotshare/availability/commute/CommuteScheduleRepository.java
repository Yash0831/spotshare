package com.spotshare.availability.commute;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Persistence for {@link CommuteSchedule}. */
public interface CommuteScheduleRepository extends JpaRepository<CommuteSchedule, UUID> {

    List<CommuteSchedule> findBySpaceIdOrderByDayOfWeekAsc(UUID spaceId);

    /** Active schedules for one space, in weekday order. */
    List<CommuteSchedule> findBySpaceIdAndActiveTrueOrderByDayOfWeekAsc(UUID spaceId);

    /** Every active schedule — the materializer sweeps these. */
    List<CommuteSchedule> findByActiveTrue();

    /** One entry per weekday per space: this is the duplicate-entry check. */
    boolean existsBySpaceIdAndDayOfWeek(UUID spaceId, int dayOfWeek);

    /** Distinct spaces with at least one active schedule — the sweep's work list. */
    @Query("select distinct s.space.id from CommuteSchedule s where s.active = true")
    List<UUID> findActiveSpaceIds();
}
