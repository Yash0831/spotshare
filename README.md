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

**Phase 2 — Parking listing, implemented and tested.** What works today:

- Everything from Phase 1 (accounts, JWT auth, refresh rotation)
- Host one-time space setup: location + coordinates, parking type, photos,
  parking/access instructions, and the authorization confirmation
  ("I confirm that I own, control, or have permission to share this parking
  space." — stored with a timestamp; creation is rejected without it)
- `POST /api/v1/spaces`, `GET /api/v1/spaces/mine`, `GET /api/v1/spaces/{id}`
  (owner view), `PUT /api/v1/spaces/{id}`, `DELETE /api/v1/spaces/{id}`
  (soft-deactivates: `active=false`, record retained, idempotent)
- Photo upload (`POST /api/v1/spaces/{id}/photos`, multipart; JPEG/PNG/WebP,
  max 5 MB, max 8 per space; bytes stored in Postgres — no S3, no fake URLs)
  and `DELETE /api/v1/spaces/{id}/photos/{photoId}`; the content endpoint
  `GET /api/v1/spaces/{id}/photos/{photoId}/content` is public by design
  (photos carry no private location data)
- Privacy by design: exact address, space label, and parking instructions are
  returned only to the space's host (owner DTO); another user gets a 403,
  never the data. Discovery (Phase 4) gets its own privacy-safe DTO.
- PostGIS extension enabled; `parking_spaces.geom` is a generated
  `geography(Point, 4326)` kept in sync with lat/lng (indexed; geographic
  search reads it in Phase 4)
- React: "My Parking" page (list, status, deactivate), the 5-step setup
  wizard (Location → Parking type → Photos → Instructions → Authorization),
  and an owner space-management page (photos, edit, deactivate)
- Flyway migrations for `parking_spaces` + `parking_photos`

What does **not** exist yet: availability/sharing, search, maps, reservations,
payments — those are later phases. Nothing is deployed; there are no real
users.

**Verification note:** the V2 migration SQL was applied against real
PostgreSQL 16 with PostGIS 3 and its constraints exercised (state regex,
latitude/longitude ranges, parking-type check, generated `geom` point,
photo bytea insert). The backend test suite is green (unit + MockMvc slice
tests, 35 total). The database-backed integration test runs where a database
is reachable from the JVM — it aborts cleanly in sandboxes that block
database connections. Live boot + the curl walkthrough below have not been
run in this environment yet; run them wherever you have Docker/a local
Postgres.

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
