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

**Phase 1 — Foundation, implemented and tested.** What works today:

- User accounts: register / login / logout, bcrypt(12) password hashing
- JWT access tokens (15 min) + single-use rotating refresh tokens (SHA-256
  hashes stored, old token rejected after rotation)
- `GET /api/v1/me` profile lookup; `USER`/`ADMIN` roles; friendly
  `{code, message, correlationId}` errors, never internals
- React app shell: typed API client with silent token refresh, auth context,
  Login / Register / Home pages, mobile-first basics
- Flyway migrations for `users` + `refresh_tokens` (no PostGIS yet)

What does **not** exist yet: parking spaces, availability, search, maps,
reservations, photos, payments — those are later phases. Nothing is deployed;
there are no real users. The parking workflows are stubbed out of the UI
intentionally until their phases land.

**Verification note:** the Flyway migration SQL was applied and its
constraints exercised (unique email, FK, cascade delete) against real
PostgreSQL 16, and the backend test suite is green (unit + MockMvc slice
tests). The database-backed integration test runs where a database is
reachable from the JVM — it aborts cleanly in sandboxes that block database
connections. Live boot + the curl walkthrough below have not been run in
this environment yet; run them wherever you have Docker/a local Postgres.

Product spec: `docs/v1-spec.md`.

## Local run

```bash
# 1. Configure secrets
cp .env.example .env   # set DB_PASSWORD and JWT_SECRET (long random string) at minimum

# 2. Start the database (PostGIS image; Phase 1 uses plain Postgres features)
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
