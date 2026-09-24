-- V1: SpotShare core schema.
-- Flyway owns the schema; Hibernate only validates (spring.jpa.hibernate.ddl-auto=validate).

-- Anti-double-booking needs GiST operators for uuid equality + tstzrange overlap.
CREATE EXTENSION IF NOT EXISTS btree_gist;

-- ---------------------------------------------------------------------------
-- users: any user can act as host and/or driver (personas, not roles).
-- ---------------------------------------------------------------------------
CREATE TABLE users (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name          varchar(255) NOT NULL,
    email         varchar(255) NOT NULL UNIQUE,
    password_hash varchar(255) NOT NULL,
    role          varchar(16)  NOT NULL CHECK (role IN ('USER', 'ADMIN')),
    created_at    timestamptz  NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- parking_spaces: a host lists a space only when authorized to share it.
-- address + instructions are PRIVATE (revealed only after a reservation is
-- CONFIRMED, to the booking driver and the host). display_area is the public
-- label shown in search results.
-- ---------------------------------------------------------------------------
CREATE TABLE parking_spaces (
    id                         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    host_id                    uuid NOT NULL REFERENCES users (id),
    address                    varchar(255) NOT NULL,
    city                       varchar(100) NOT NULL,
    state                      varchar(50)  NOT NULL,
    zip_code                   varchar(20)  NOT NULL,
    latitude                   double precision NOT NULL,
    longitude                  double precision NOT NULL,
    display_area               varchar(255) NOT NULL,
    space_type                 varchar(32)  NOT NULL
        CHECK (space_type IN ('DRIVEWAY','GARAGE','PARKING_LOT','ASSIGNED_SPACE','EV_SPACE','OTHER_PRIVATE_SPACE')),
    description                text,
    hourly_price               numeric(10,2) NOT NULL CHECK (hourly_price >= 0),
    max_vehicle_size           varchar(100),
    height_restriction         varchar(50),
    instructions               text,
    active                     boolean NOT NULL DEFAULT true,
    authorization_confirmed    boolean NOT NULL,
    authorization_confirmed_at timestamptz,
    created_at                 timestamptz NOT NULL DEFAULT now(),
    updated_at                 timestamptz NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- availability_windows: a reservation [arrival, departure) must be fully
-- contained within ONE window. No recurring schedules in V1.
-- ---------------------------------------------------------------------------
CREATE TABLE availability_windows (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    space_id   uuid NOT NULL REFERENCES parking_spaces (id) ON DELETE CASCADE,
    starts_at  timestamptz NOT NULL,
    ends_at    timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT availability_window_order CHECK (ends_at > starts_at)
);

-- ---------------------------------------------------------------------------
-- reservations: double booking is impossible at the DB level. reserved_period
-- is a half-open [arrival, departure) tstzrange generated from the instants;
-- the EXCLUDE constraint rejects a second CONFIRMED row whose period overlaps
-- on the same space. Half-open: a reservation ending at 4 PM does NOT conflict
-- with one starting at 4 PM.
-- ---------------------------------------------------------------------------
CREATE TABLE reservations (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    space_id        uuid NOT NULL REFERENCES parking_spaces (id),
    driver_id       uuid NOT NULL REFERENCES users (id),
    arrival         timestamptz NOT NULL,
    departure       timestamptz NOT NULL,
    reserved_period tstzrange GENERATED ALWAYS AS (tstzrange(arrival, departure, '[)')) STORED,
    total_price     numeric(10,2),
    status          varchar(32) NOT NULL
        CHECK (status IN ('CONFIRMED','COMPLETED','CANCELLED_BY_DRIVER','CANCELLED_BY_HOST')),
    cancelled_by    uuid REFERENCES users (id),
    cancelled_at    timestamptz,
    cancel_reason   text,
    payment_status  varchar(32) NOT NULL DEFAULT 'NOT_REQUIRED',
    version         bigint NOT NULL DEFAULT 0,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT reservation_order CHECK (departure > arrival)
);

ALTER TABLE reservations
    ADD CONSTRAINT no_double_booking
    EXCLUDE USING gist (
        space_id WITH =,
        reserved_period WITH &&
    )
    WHERE (status = 'CONFIRMED');

-- ---------------------------------------------------------------------------
-- parking_photos: photos are PUBLIC (visible in search results). The binary
-- lives in S3-compatible storage; only the storage key is recorded here.
-- ---------------------------------------------------------------------------
CREATE TABLE parking_photos (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    space_id    uuid NOT NULL REFERENCES parking_spaces (id) ON DELETE CASCADE,
    storage_key varchar(512) NOT NULL,
    content_type varchar(128),
    sort_order  integer NOT NULL DEFAULT 0,
    created_at  timestamptz NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- refresh_tokens: rotating refresh tokens; only the hash is stored (single-use
-- rotation enforced by the auth service in Phase 4).
-- ---------------------------------------------------------------------------
CREATE TABLE refresh_tokens (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash varchar(255) NOT NULL UNIQUE,
    expires_at timestamptz NOT NULL,
    revoked    boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now()
);

-- ---------------------------------------------------------------------------
-- Indexes
-- ---------------------------------------------------------------------------
CREATE INDEX idx_parking_spaces_active_geo
    ON parking_spaces (active, latitude, longitude);
CREATE INDEX idx_reservations_space_period
    ON reservations (space_id, arrival, departure);
CREATE INDEX idx_availability_windows_space_period
    ON availability_windows (space_id, starts_at, ends_at);
CREATE INDEX idx_refresh_tokens_user
    ON refresh_tokens (user_id);
CREATE INDEX idx_parking_photos_space
    ON parking_photos (space_id);
