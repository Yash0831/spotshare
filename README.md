# SpotShare

A parking-sharing marketplace for the United States: hosts list legitimate
private parking spaces, drivers discover nearby parking around a destination
and reserve it. Core loop: **DISCOVER → RESERVE → PARK**.

## Stack

- Backend: Java 17, Spring Boot 3.2.5, PostgreSQL 15 (Flyway owns the schema),
  Spring Security with JWT
- Frontend (from Phase 7): React 18 + TypeScript + Vite, Leaflet + OpenStreetMap
- Tests: JUnit 5 + Mockito + Testcontainers
- Build/CI: Maven (offline), Docker Compose, Jenkins

## Status (honest)

**Phase 1 scaffolding — no domain logic yet.** This commit contains the
compiling Spring Boot skeleton: module packages, env-driven config, a
correlation-ID filter, the global error envelope, a health endpoint, Docker
Compose, a multi-stage Dockerfile, `.env.example`, `render.yaml`, and a
Jenkinsfile skeleton. Entities, migrations, auth, search, and reservations
arrive in later phases. Nothing here is deployed; there are no users.

## Local run

```bash
# 1. Configure secrets
cp .env.example .env   # fill in DB_PASSWORD and JWT_SECRET at minimum

# 2. Start Postgres + the API
docker compose up --build

# 3. Health check
curl -i http://localhost:8080/api/v1/health
```

Build the backend only (offline; `/root/.m2` must be seeded first):

```bash
export JAVA_HOME=/opt/jdk PATH=/opt/jdk/bin:/opt/maven/bin:$PATH
cd backend && mvn -o -q -DskipTests compile
```

## Configuration

All runtime values come from environment variables; see `.env.example`.
Key ones: `PORT`, `DB_HOST`/`DB_PORT`/`DB_NAME`/`DB_USER`/`DB_PASSWORD`,
`DB_URL_PARAMS` (e.g. `?sslmode=require` for Neon), `JWT_SECRET`,
`APP_CORS_ALLOWED_ORIGINS` (comma-separated), `S3_*` (photo storage; uploads
return 501 when unset), `NOMINATIM_USER_AGENT`.

## Docs

Full documentation lands in Phase 10 (`docs/`). See
`~/workspace/spotshare-build/SPEC.md` for the product spec in the meantime.

## License

MIT (license file lands in Phase 10).
