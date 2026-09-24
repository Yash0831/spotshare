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

**Phase 3 — Temporary availability, implemented and tested.** What works today:

- Everything from Phase 2 (accounts, JWT auth, space setup, photos)
- The signature **"I'm leaving / Share My Spot"** flow: one tap starts a
  temporary availability window for a space — pick a return time (quick chips
  1h / 2h / 4h or a custom time; defaults to +2h), choose **Free** or an hourly
  price (max $100/hr; null means free), confirm — the spot is immediately
  available to others and **expires automatically** at the return time
  (expiry is derived — a window is live while `startsAt <= now < endsAt` —
  never scheduled or stored)
- Availability rules, enforced server-side: host-only, active spaces only,
  return time must be in the future, minimum 30 minutes, hourly price
  1–10,000 cents, **overlapping windows for one space are rejected (409)**;
  adjacent windows are allowed
- `POST /api/v1/spaces/{id}/availability`, `GET /api/v1/spaces/{id}/availability`
  (upcoming + live for one space), `GET /api/v1/availability/mine`
  (across all of the host's spaces), `DELETE /api/v1/availability/{windowId}`
  (removes a not-yet-started share; idempotent — a missing window is a no-op)
- Derived display state on every space DTO: `OFFLINE` (deactivated), `PRIVATE`
  (no live share), `AVAILABLE` (shared, >30 min left), `RETURNING` (shared,
  ≤30 min left); `RESERVED` stays reserved for Phase 5
- React: **Share my spot** sheet on the space page (return-time chips, custom
  picker, free/hourly toggle, cents-safe price input, live summary), per-space
  and cross-space "currently sharing" lists with two-tap remove for
  not-started shares, and status badges driven by the backend `displayState`
- Flyway migration `V3__create_availability_windows.sql`
  (`MANUAL`/`COMMUTE`/`VACATION` sources; positive-price and
  end-after-start constraints; half-open overlap-safe query semantics)

What does **not** exist yet: discovery/search, maps, reservations, payments —
those are later phases. Nothing is deployed; there are no real users.

**Verification note:** V1–V3 migrations were applied against real
PostgreSQL 16 with PostGIS 3 and V3 constraints exercised (invalid order,
zero/negative rate, invalid source rejected; adjacent windows not counted as
overlapping). The backend suite is green (68 tests: unit + MockMvc + web
slices); the frontend suite is green (30 tests: components + pages +
utils). The database-backed integration tests run where a database is
reachable from the JVM — they abort cleanly in sandboxes that block
database connections. Docker Compose cannot run in this sandbox, so live
boot and the curl walkthroughs have not been executed here; run them
wherever you have Docker/a local Postgres.

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
