-- V1: users + refresh tokens (Phase 1: auth + accounts).
-- Flyway owns the schema; Hibernate only validates (spring.jpa.hibernate.ddl-auto=validate).
--
-- NOTE: this migration replaced the broader V1__create_core_schema.sql from the
-- pre-spec commits (nothing ever ran Flyway against it — no deployed environment
-- exists yet), so Phase 1 owns only the tables it needs. Parking, availability,
-- reservation, and photo tables arrive with their own migrations in later phases.

CREATE TABLE users (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    email         varchar(255) NOT NULL UNIQUE,
    password_hash varchar(255) NOT NULL,
    first_name    varchar(100) NOT NULL,
    last_name     varchar(100) NOT NULL,
    phone         varchar(32),
    role          varchar(16)  NOT NULL CHECK (role IN ('USER', 'ADMIN')),
    created_at    timestamptz  NOT NULL DEFAULT now(),
    updated_at    timestamptz  NOT NULL DEFAULT now()
);

-- Rotating refresh tokens. Only the SHA-256 hash of the opaque token is stored;
-- the raw token is never persisted. Single-use rotation is enforced by AuthService.
CREATE TABLE refresh_tokens (
    id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash varchar(255) NOT NULL UNIQUE,
    expires_at timestamptz NOT NULL,
    revoked    boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_refresh_tokens_user ON refresh_tokens (user_id);
