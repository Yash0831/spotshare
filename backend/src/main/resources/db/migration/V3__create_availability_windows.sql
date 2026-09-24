-- V3: availability windows (Phase 3: "I'm leaving" / Share My Spot).
--
-- A window covers the half-open period [starts_at, ends_at). Expiry is
-- DERIVED from these timestamps (a window is live while starts_at <= now <
-- ends_at) — there is no status column and no expiry job; ended windows
-- simply stop being live. Overlapping windows for the same space are
-- rejected by the application (a reservation must be contained in one
-- unambiguous window, Phase 5), so no exclusion constraint here.
--
-- hourly_rate_cents is NULL for a free share. Money is integer cents —
-- never floating point.

CREATE TABLE availability_windows (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    space_id          uuid NOT NULL REFERENCES parking_spaces (id) ON DELETE CASCADE,
    starts_at         timestamptz NOT NULL,
    ends_at           timestamptz NOT NULL,
    source            varchar(16) NOT NULL CHECK (source IN ('MANUAL', 'COMMUTE', 'VACATION')),
    hourly_rate_cents integer CHECK (hourly_rate_cents IS NULL OR hourly_rate_cents > 0),
    created_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_availability_window_order CHECK (ends_at > starts_at)
);

CREATE INDEX idx_availability_space_period ON availability_windows (space_id, starts_at, ends_at);
