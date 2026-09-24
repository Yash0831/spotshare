# SpotShare

A parking-sharing marketplace for the United States: people leaving a private
parking spot share it until they return; drivers nearby find and reserve it.
Core loop: **I'M LEAVING → SHARE → DISCOVER → RESERVE → PARK → RETURN.**

## Stack

- Backend: Java 21, Spring Boot 3.2.5, PostgreSQL 15 (Flyway owns the schema),
  Spring Security with JWT (15-min access + rotating refresh tokens)
- Frontend: React 18 + TypeScript + Vite, Tailwind CSS
- Tests: JUnit 5, MockMvc, Vitest + Testing Library
- Build: Maven (offline), Docker Compose (database only)

## Status (honest)

**Phase 5 — Reservations, implemented and tested.** What works today:

- Everything from Phase 4, plus reservations:
  `POST /api/v1/reservations` with `spaceId`, `arrival`, `departure`
  (all times are half-open `[arrival, departure)` — an arrival exactly at
  another reservation's departure is fine) and an optional
  `Idempotency-Key` header
- Booking rules: the period must sit inside a single availability window,
  the space must be active, and you can't book your own space. Arrival gets a
  60-second clock-skew grace against "in the past" validation. No payment is
  collected — this is beta; the reservation holds the spot
- Pricing: total is prorated from the host's hourly rate with `HALF_UP`
  rounding to the cent (the rate is snapshotted at booking time); free
  shares book for $0. Confirmation codes look like `SP-K84D2`
- Double-booking prevention (three layers): a per-space PostgreSQL advisory
  transaction lock serializes concurrent attempts, an application-level
  overlap check, and a GiST exclusion constraint backstop
  (`EXCLUDE USING gist (space_id WITH =, period WITH &&) WHERE (status =
  'CONFIRMED')`) — so two overlapping requests for one space yield exactly
  one booking, and the loser gets a friendly 409 `SPACE_JUST_RESERVED`,
  never raw SQL
- Idempotency: one stable `Idempotency-Key` per driver; a repeat POST with
  the same key returns the original reservation (`created=false`), and a
  race between two concurrent same-key requests returns the winner's
  reservation — never a duplicate
- Cancellation: a driver can cancel an upcoming reservation
  (`POST /api/v1/reservations/{id}/cancel`); cancelling again returns the same
  cancelled reservation (idempotent). A cancelled period becomes bookable
  again; only `CONFIRMED` reservations block overlaps
- **Privacy by construction:** reservation lists and summaries never include
  the exact address, space label, or parking instructions. Those appear only
  in the authorized detail response (`GET /api/v1/reservations/{id}`) for
  the driver or the host
- Discovery now excludes spaces with a conflicting confirmed reservation —
  the gap documented in Phase 4 is closed
- React: **Reserve** page (arrival/departure pickers constrained to the host
  window, live prorated estimate, double-submit guard, stable idempotency
  key across retries, "Beta — no payment is collected. Your reservation
  holds the spot." notice), **My Reservations** (upcoming + past, address
  withheld in summaries), reservation **detail** (confirmation code, exact
  address, label, instructions, cancel), and a fifth bottom-tab ("Reserved")
  alongside Explore, Park Now, My Parking, Profile

What does **not** exist yet: payments — those are later phases. Nothing is
deployed; there are no real users.

**Verification note:** V1–V4 migrations applied cleanly against real
PostgreSQL 16.15 + PostGIS 3.4.2, and the exclusion constraint was proven
with two concurrent `psql` sessions: session B's overlapping insert blocked
while session A held its transaction, then failed with
`excl_reservations_no_overlap` once A committed — exactly one booking
survived. Adjacent periods, cancelled-period re-booking, and same-period
bookings on a different space all succeed. The backend suite is green
(136 tests: unit + MockMvc + web slices); the frontend suite is green
(75 tests). The database-backed integration tests run where a database is
reachable from the JVM — they abort cleanly in sandboxes that block JVM
database connections, so the live Java concurrency test did not execute
here; the equivalent proof was done with `psql` instead. Docker Compose
cannot run in this sandbox, so live boot, real Nominatim calls, and the
curl walkthroughs have not been executed here; run them wherever you have
Docker/a local Postgres. No visual map verification was performed in this
sandbox.

Product spec: `docs/v1-spec.md`.

## Local run

```bash
# 1. Configure secrets
cp .env.example .env   # set DB_PASSWORD and JWT_SECRET (long random string) at minimum

# 2. Start the database (PostGIS image; Phase 2 uses the PostGIS extension)
docker compose up -d

# 3. Run the backend (Flyway migrates automatically on boot)
export JAVA_HOME=/opt/jdk21 PATH=/opt/jdk21/bin:/opt/maven/bin:$PATH
cd backend && mvn -o spring-boot:run
# → http://localhost:8080

# 4. Run the frontend
cd frontend && npm install && npm run dev
# → http://localhost:5173 (API proxied to :8080)
```

Health check: `curl http://localhost:8080/api/v1/health` → `{"status":"UP"}`.

## Manual auth check (curl)

```bash
# Register
curl -s -X POST localhost:8080/api/v1/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"email":"sam@example.com","password":"correct-horse-9","firstName":"Sam","lastName":"Reyes"}'
# → 201 with accessToken, refreshToken, user (no password hash)

# Login
curl -s -X POST localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"sam@example.com","password":"correct-horse-9"}'

# Me (replace $ACCESS)
curl -s localhost:8080/api/v1/me -H "Authorization: Bearer $ACCESS"

# Rotate the refresh token (replace $REFRESH); the old one stops working
curl -s -X POST localhost:8080/api/v1/auth/refresh \
  -H 'Content-Type: application/json' \
  -d "{\"refreshToken\":\"$REFRESH\"}"

# Logout
curl -s -X POST localhost:8080/api/v1/auth/logout \
  -H 'Content-Type: application/json' \
  -d "{\"refreshToken\":\"$NEW_REFRESH\"}" -o /dev/null -w '%{http_code}\n'
# → 204
```

## Manual space check (curl)

```bash
# Create a space (replace $ACCESS) — authorizationConfirmed is required
curl -s -X POST localhost:8080/api/v1/spaces \
  -H "Authorization: Bearer $ACCESS" -H 'Content-Type: application/json' \
  -d '{"label":"B17","address":"123 Wacker Dr","city":"Chicago","state":"IL",
       "zipCode":"60601","latitude":41.8858,"longitude":-87.6189,
       "areaLabel":"West Loop","parkingType":"ASSIGNED_SPACE",
       "vehicleSizes":["SEDAN","SUV"],"covered":true,
       "parkingInstructions":"Gate code 1234, level 2.",
       "authorizationConfirmed":true}'
# → 201; owner DTO includes the exact address + authorizationConfirmedAt

# My spaces
curl -s localhost:8080/api/v1/spaces/mine -H "Authorization: Bearer $ACCESS"

# Upload a photo (replace $SPACE)
curl -s -X POST localhost:8080/api/v1/spaces/$SPACE/photos \
  -H "Authorization: Bearer $ACCESS" -F "photo=@/path/to/photo.jpg"
# → 201 with contentUrl; the bytes are served publicly at that URL

# Another user's token gets 403 on GET /spaces/$SPACE — never the data

# Deactivate (soft delete; idempotent)
curl -s -X DELETE localhost:8080/api/v1/spaces/$SPACE -H "Authorization: Bearer $ACCESS"
# → 200 with "active":false
```

## Manual share check (curl)

```bash
# Share my spot — free, returning in 2h (replace $ACCESS and $SPACE)
curl -s -X POST localhost:8080/api/v1/spaces/$SPACE/availability \
  -H "Authorization: Bearer $ACCESS" -H 'Content-Type: application/json' \
  -d "{\"returnTime\":\"$(date -u -d '+2 hours' +%Y-%m-%dT%H:%M:%SZ)\",\"hourlyRateCents\":null}"
# → 201; window starts immediately, ends at the return time

# Paid share — $3.50/hour
curl -s -X POST localhost:8080/api/v1/spaces/$SPACE/availability \
  -H "Authorization: Bearer $ACCESS" -H 'Content-Type: application/json' \
  -d "{\"returnTime\":\"$(date -u -d '+4 hours' +%Y-%m-%dT%H:%M:%SZ)\",\"hourlyRateCents\":350}"
# → 409 OVERLAPPING_WINDOW if it overlaps an existing share

# Upcoming + live shares for one space
curl -s localhost:8080/api/v1/spaces/$SPACE/availability -H "Authorization: Bearer $ACCESS"

# All my shares across my spaces
curl -s localhost:8080/api/v1/availability/mine -H "Authorization: Bearer $ACCESS"

# Remove a share that hasn't started yet (idempotent; replace $WINDOW)
curl -s -X DELETE localhost:8080/api/v1/availability/$WINDOW -H "Authorization: Bearer $ACCESS"
# → 204; shares end themselves at the return time — no scheduled job exists
```

## Manual reservation check (curl)

```bash
# Reserve (replace $ACCESS and $SPACE; the period must sit inside a shared window)
# Arrival has a 60-second clock-skew grace against "in the past"
curl -s -X POST localhost:8080/api/v1/reservations \
  -H "Authorization: Bearer $ACCESS" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: 550e8400-e29b-41d4-a716-446655440000" \
  -d "{\"spaceId\":\"$SPACE\",\"arrival\":\"$(date -u -d '+1 hour' +%Y-%m-%dT%H:%M:%SZ)\",\"departure\":\"$(date -u -d '+3 hours' +%Y-%m-%dT%H:%M:%SZ)\"}"
# → 201 with the reservation + confirmation code (e.g. SP-K84D2); total is
#   prorated from the host's hourly rate (free shares book for $0)
# → 409 SPACE_JUST_RESERVED if someone booked it first; a repeat POST with
#   the same Idempotency-Key returns the original reservation

# My reservations (upcoming + past; summaries omit the exact address)
curl -s localhost:8080/api/v1/reservations/mine -H "Authorization: Bearer $ACCESS"
# ?filter=upcoming|active|past also supported

# Detail (driver or host only; exact address, space label, and host
# instructions are revealed here and nowhere else)
curl -s localhost:8080/api/v1/reservations/$RES -H "Authorization: Bearer $ACCESS"

# Cancel an upcoming reservation (idempotent; replace $RES)
curl -s -X POST localhost:8080/api/v1/reservations/$RES/cancel -H "Authorization: Bearer $ACCESS"
# → 200 with the cancelled reservation; cancelling again returns the same
#   state, and the period becomes bookable again
```

## Configuration

All runtime values come from environment variables; see `.env.example`.
Key ones: `PORT`, `DB_HOST`/`DB_PORT`/`DB_NAME`/`DB_USER`/`DB_PASSWORD`,
`DB_URL_PARAMS` (e.g. `?sslmode=require` for hosted Postgres), `JWT_SECRET`
(refuses to boot under the `prod` profile when blank/short),
`APP_CORS_ALLOWED_ORIGINS` (comma-separated).

## Docs

`docs/v1-spec.md` is the product spec and phase plan. Deeper docs
(architecture, API reference, deployment) land in Phase 11.

## License

MIT (license file lands in Phase 11).
