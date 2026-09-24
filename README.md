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

**Phase 4 — Discovery, implemented and tested.** What works today:

- Everything from Phase 3, plus public nearby search:
  `GET /api/v1/spaces/search?lat=&lng=&arrival=&departure=` with optional
  `radiusMiles` (0.5–25, default 3), `maxPrice` (dollars; free shares always
  match), `covered`, `evCharging`, `vehicleSize`, and `page`/`size` pagination
- PostGIS search: `ST_DWithin` radius filtering, KNN (`<->`) nearest-first
  ordering, and a **complete-period containment** rule — only windows with
  `starts_at <= arrival AND ends_at >= departure` match; when several windows
  contain the period, the cheapest wins
- **Privacy by construction:** the public DTO carries no exact address, no
  space label/number, no parking instructions, and no host contact — only an
  approximate location (coordinates rounded to ~110 m + area label), the host's
  first name + last initial, price, distance, photos, and the containing window.
  The exact address is revealed only after a reservation (Phase 5)
- `GET /api/v1/geocode?q=` — address lookup via Nominatim (OpenStreetMap),
  behind a `GeocodingProvider` interface with a configurable User-Agent,
  US-scoped, ~1 req/s self-throttling, and a friendly `GEOCODER_UNAVAILABLE`
  503 when the provider can't be reached. Set `NOMINATIM_USER_AGENT` (see
  `.env.example`) to something identifying before any real traffic — the
  Nominatim usage policy requires it
- React: **Explore** (destination autocomplete + date/time + filters → map
  with price pins + result list → public detail page), **Park Now**
  (geolocation → immediate 2-hour search, with manual destination fallback
  when location is denied/unavailable), bottom tab bar
  (Explore · Park Now · My Parking · Profile), Leaflet + OpenStreetMap tiles
  (no API keys)
- ⚠️ **Reservation conflicts are NOT filtered yet.** A share that will be
  booked in Phase 5 can still appear in Phase 4 results. Conflict filtering
  arrives with reservations in Phase 5 — this is a known, documented gap,
  not an oversight.

What does **not** exist yet: reservations, payments — those are later
phases. Nothing is deployed; there are no real users.

**Verification note:** no new migrations in Phase 4. The native PostGIS
search SQL was executed against real PostgreSQL 16 + PostGIS 3 on a scratch
database with the V1–V3 schema: radius filtering, complete-period containment
(partial windows excluded), cheapest-window selection, `maxPrice`
(including free shares matching), covered/EV/vehicle-size filters, and
nearest-first ordering all verified against seeded data. The backend suite is
green (96 tests: unit + MockMvc + web slices + privacy allowlist); the
frontend suite is green (58 tests, including a real Leaflet map render in
jsdom). The database-backed integration tests run where a database is
reachable from the JVM — they abort cleanly in sandboxes that block database
connections. Docker Compose cannot run in this sandbox, so live boot, real
Nominatim calls, and the curl walkthroughs have not been executed here; run
them wherever you have Docker/a local Postgres. No visual map verification
was performed in this sandbox.

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
