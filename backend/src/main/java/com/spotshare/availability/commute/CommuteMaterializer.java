package com.spotshare.availability.commute;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * The commute materializer (spec §8): every hour, every space with at least
 * one active commute schedule is rolled 14 days out with real
 * {@code availability_windows} (source=COMMUTE).
 *
 * <p>Materialization itself is idempotent
 * ({@link CommuteService#materializeSpace}), so an overlapping run can never
 * create a duplicate: the same (space, period, COMMUTE) window is skipped,
 * with the natural unique key on
 * {@code (space_id, starts_at, ends_at, source)} as the final backstop.
 * New schedules also materialize immediately at creation/resume time, so the
 * host sees the pattern take effect right away — this job is the roll-forward.
 */
@Service
public class CommuteMaterializer {

    /** How far out schedules stay materialized. */
    public static final int DAYS_AHEAD = 14;

    private final CommuteScheduleRepository schedules;
    private final CommuteService commutes;

    public CommuteMaterializer(CommuteScheduleRepository schedules, CommuteService commutes) {
        this.schedules = schedules;
        this.commutes = commutes;
    }

    /**
     * Hourly roll-forward. Each space gets its own transaction
     * ({@link CommuteService#materializeSpace}), so one space's failure never
     * blocks the rest of the sweep.
     */
    @Scheduled(fixedDelay = 3_600_000)
    public void materializeAll() {
        for (var spaceId : schedules.findActiveSpaceIds()) {
            commutes.materializeSpace(spaceId);
        }
    }
}
