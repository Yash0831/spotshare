-- V2: parking spaces + photos (Phase 2: host listing setup).
--
-- parking_spaces carries the private exact address and parking instructions;
-- the public area_label is what discovery shows (Phase 4 DTOs enforce this).
-- geom is a generated PostGIS geography point kept in sync with lat/lng by the
-- database; it is the indexed truth for geographic search (Phase 4 reads it).
--
-- Photos are stored as bytea in this V1 ("bytea in DB is the simplest honest
-- V1 storage"): no S3, no fake CDN URLs. The content endpoint serves them by id.

-- PostGIS is genuinely needed for meter-based geographic radius search
-- (see docs/v1-spec.md §10). The postgis/postgis image ships the extension;
-- CREATE EXTENSION needs database ownership, which the app user has on its
-- own database (and the postgres superuser owns the test database).
CREATE EXTENSION IF NOT EXISTS postgis;

CREATE TABLE parking_spaces (
    id                        uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    host_id                   uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    label                     varchar(64) NOT NULL,
    address                   varchar(255) NOT NULL,
    city                      varchar(128) NOT NULL,
    state                     varchar(2) NOT NULL CHECK (state ~ '^[A-Z]{2}$'),
    zip_code                  varchar(10) NOT NULL,
    latitude                  double precision NOT NULL CHECK (latitude BETWEEN -90 AND 90),
    longitude                 double precision NOT NULL CHECK (longitude BETWEEN -180 AND 180),
    -- Generated from lat/lng; indexed truth for geographic search. lat/lng stay
    -- as plain columns for readability/debugging.
    geom                      geography(Point, 4326)
                                  GENERATED ALWAYS AS
                                  (ST_SetSRID(ST_MakePoint(longitude, latitude), 4326)::geography)
                                  STORED,
    area_label                varchar(128) NOT NULL,
    parking_type              varchar(32) NOT NULL CHECK (parking_type IN
                                  ('DRIVEWAY', 'PRIVATE_GARAGE', 'ASSIGNED_SPACE',
                                   'PRIVATE_LOT', 'EV_SPACE', 'OTHER_PRIVATE')),
    description               varchar(2000),
    vehicle_sizes             text[] NOT NULL DEFAULT '{}',
    height_limit_inches       integer CHECK (height_limit_inches IS NULL OR height_limit_inches > 0),
    covered                   boolean NOT NULL DEFAULT false,
    ev_charging               boolean NOT NULL DEFAULT false,
    -- Private: revealed only to the space's host (Phase 2) and, later, to a
    -- reservation's driver after confirmation (Phase 5).
    parking_instructions      varchar(2000),
    authorization_confirmed   boolean NOT NULL DEFAULT false,
    authorization_confirmed_at timestamptz,
    -- Deactivation is soft: DELETE sets active=false; the row is never removed.
    active                    boolean NOT NULL DEFAULT true,
    created_at                timestamptz NOT NULL DEFAULT now(),
    updated_at                timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_parking_spaces_geom ON parking_spaces USING GIST (geom);
CREATE INDEX idx_parking_spaces_host ON parking_spaces (host_id);
CREATE INDEX idx_parking_spaces_active ON parking_spaces (active);

CREATE TABLE parking_photos (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    space_id     uuid NOT NULL REFERENCES parking_spaces (id) ON DELETE CASCADE,
    data         bytea NOT NULL,
    content_type varchar(64) NOT NULL,
    sort_order   integer NOT NULL DEFAULT 0,
    created_at   timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_parking_photos_space ON parking_photos (space_id);
