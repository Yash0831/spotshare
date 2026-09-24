-- V5: commute mode (Phase 8: "Commute mode" / weekly recurring availability).
--
-- commute_schedules: one row per weekday entry. day_of_week is 0=Monday ..
-- 6=Sunday. start_time/end_time are local times-of-day in the schedule's IANA
-- timezone — the materializer converts them to real instants per calendar
-- date. hourly_rate_cents is NULL for a free commute window; money stays in
-- integer cents. active=false means paused: the materializer skips the row
-- and future unreserved COMMUTE windows for the space are removed (windows
-- with a confirmed reservation are never touched).
--
-- One entry per weekday per space: a commuter's pattern is one stretch per
-- day, and the unique key makes "no overlapping schedules for the same
-- weekday" trivially true.
--
-- The natural unique key on availability_windows is the idempotency backstop
-- for the materializer (spec §8): even if the job ever ran twice, the same
-- (space, period, source) can only be inserted once. Existing MANUAL windows
-- have distinct starts_at timestamps, so the constraint applies cleanly.

CREATE TABLE commute_schedules (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    space_id          uuid NOT NULL REFERENCES parking_spaces (id) ON DELETE CASCADE,
    day_of_week       smallint NOT NULL CHECK (day_of_week BETWEEN 0 AND 6),
    start_time        time NOT NULL,
    end_time          time NOT NULL,
    hourly_rate_cents integer CHECK (hourly_rate_cents IS NULL
                                     OR (hourly_rate_cents >= 1 AND hourly_rate_cents <= 10000)),
    timezone          varchar(64) NOT NULL DEFAULT 'UTC',
    active            boolean NOT NULL DEFAULT true,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_commute_time_order CHECK (end_time > start_time),
    CONSTRAINT uq_commute_weekday UNIQUE (space_id, day_of_week)
);

CREATE INDEX idx_commute_schedules_active ON commute_schedules (space_id) WHERE active;

ALTER TABLE availability_windows
    ADD CONSTRAINT uq_availability_window_natural
        UNIQUE (space_id, starts_at, ends_at, source);
