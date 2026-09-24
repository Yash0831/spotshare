-- V4: reservations (Phase 5: booking).
--
-- A reservation books the half-open period [arrival, departure) inside a
-- single availability window. Money is integer cents — hourly_rate_cents is
-- NULL for a free share (snapshotted from the window at booking time);
-- total_cents is computed at booking time and stored.
--
-- Anti-double-booking is enforced at the database level: an exclusion
-- constraint rejects two CONFIRMED reservations whose periods overlap for
-- the same space (requires btree_gist for the uuid equality operator).
-- The application also takes a per-space advisory lock and checks for
-- conflicts inside the transaction (spec §9); the constraint is the final
-- backstop, and its violation is translated to a friendly 409.
--
-- code is 'SP-' + 5 chars from an unambiguous alphabet (no 0/O, 1/I),
-- generated in Java. idempotency_key is scoped per driver: the same
-- (driver_id, key) pair returns the original reservation instead of a
-- duplicate (mobile double-tap safety).

CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE reservations (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    space_id          uuid NOT NULL REFERENCES parking_spaces (id) ON DELETE CASCADE,
    driver_id         uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    arrival           timestamptz NOT NULL,
    departure         timestamptz NOT NULL,
    period            tstzrange GENERATED ALWAYS AS (tstzrange(arrival, departure, '[)')) STORED,
    hourly_rate_cents integer CHECK (hourly_rate_cents IS NULL OR hourly_rate_cents > 0),
    total_cents       integer NOT NULL CHECK (total_cents >= 0),
    status            varchar(16) NOT NULL
                          CHECK (status IN ('CONFIRMED', 'CANCELLED', 'COMPLETED')),
    code              varchar(8) NOT NULL,
    idempotency_key   varchar(64),
    cancelled_at      timestamptz,
    cancelled_by      varchar(16)
                          CHECK (cancelled_by IS NULL OR cancelled_by IN ('DRIVER', 'HOST')),
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_reservation_period CHECK (arrival < departure),
    CONSTRAINT uq_reservations_code UNIQUE (code),
    CONSTRAINT uq_reservations_idempotency UNIQUE (driver_id, idempotency_key),
    CONSTRAINT excl_reservations_no_overlap
        EXCLUDE USING gist (space_id WITH =, period WITH &&) WHERE (status = 'CONFIRMED')
);

CREATE INDEX idx_reservations_driver ON reservations (driver_id, status, arrival);
CREATE INDEX idx_reservations_space ON reservations (space_id, status);
