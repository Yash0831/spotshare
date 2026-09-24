# SpotShare — Deployment guide

This guide takes SpotShare from a fresh clone to a running production
deployment. Nothing here deploys automatically and nothing is deployed for
you: you run these steps on your own host or container platform.

## What the app needs

- **PostgreSQL with the PostGIS extension** — non-negotiable. Search uses
  `ST_DWithin` and parking spaces store a generated geography point, so a
  plain Postgres image or a Postgres service without PostGIS will not work.
- **~512 MB RAM** for the API container (JVM respects cgroup limits via
  `-XX:MaxRAMPercentage=75.0` in `backend/Dockerfile`). The frontend is
  static files on nginx; the database wants its own headroom.
- Outbound HTTPS to `nominatim.openstreetmap.org` for geocoding (driver
  destination search). The app works without it, but destination autocomplete
  degrades to an error message.
- No Redis, no Kafka, no Elasticsearch, no background workers beyond two
  in-process `@Scheduled` jobs (reservation completion, commute
  materialization).

**Free-tier friendliness:** a single small VPS (e.g. 1 vCPU / 1 GB RAM,
roughly $6–12/mo on common hosts) comfortably runs all three compose
services. If you prefer managed pieces: use a managed Postgres **with
PostGIS** (check the provider — some free tiers ship plain Postgres only),
run the API on any container host, and serve the frontend from any static
host with `VITE_API_BASE_URL` pointed at the API.

## Prerequisites

- Docker Engine 24+ with the Compose plugin, **or** Java 21 + Node 20 to run
  without containers.
- `openssl` (to generate the JWT secret).
- A domain or public IP if you want HTTPS — terminate TLS at a reverse proxy
  (Caddy, nginx, or your platform's load balancer) in front of the `web`
  service. The compose file serves plain HTTP on port 80.

## 1. Configure environment variables

```bash
cp .env.example .env
```

Edit `.env` and set at minimum:

| Variable | Required | Notes |
|---|---|---|
| `DB_PASSWORD` | yes | Strong random value: `openssl rand -base64 32` |
| `JWT_SECRET` | yes | **At least 32 characters**, random: `openssl rand -base64 48`. The prod profile refuses to boot without it. |
| `APP_CORS_ALLOWED_ORIGINS` | yes | Your frontend origin(s), comma-separated, no trailing slash — e.g. `https://app.example.com`. |
| `DB_HOST` / `DB_USER` / `DB_NAME` | if managed DB | Compose defaults to the bundled `db` service. |
| `DB_URL_PARAMS` | if managed DB | e.g. `?sslmode=require` (appended verbatim to the JDBC URL). |
| `NOMINATIM_USER_AGENT` | recommended | Identifies your deployment to Nominatim per their usage policy — an app name plus contact, e.g. `SpotShare/1.0 (ops@example.com)`. |
| `VITE_API_BASE_URL` | if split hosting | Leave at the default `/api/v1` when the frontend and API deploy together (nginx proxies `/api/` to the API, staying same-origin). Set to the API's public URL when the frontend is hosted separately. |
| `WEB_PORT` | optional | Host port for the frontend (default `80`). |

Never commit `.env`. Never reuse the development `changeme` password.

## 2. Build and start

```bash
docker compose -f docker-compose.prod.yml up -d --build
```

This starts three services:

- `db` — PostGIS 16, data in the `spotshare-pgdata-prod` volume.
- `api` — the Spring Boot API with `SPRING_PROFILES_ACTIVE=prod`. Flyway
  runs on boot and migrates the schema (`validate-on-migrate` is on; failed
  migrations fail the boot rather than half-applying).
- `web` — nginx serving the production Vite build, proxying `/api/*` to
  the API.

Check status:

```bash
docker compose -f docker-compose.prod.yml ps
docker compose -f docker-compose.prod.yml logs -f api
```

## 3. Verify the deployment

**Health checks** (both unauthenticated, by design — load balancers need them):

```bash
curl -s http://localhost:80/api/v1/health
# {"status":"UP","service":"spotshare-api"}

curl -s http://localhost:8080/actuator/health   # from inside the host/network
```

**Smoke test** — the full user workflow:

```bash
API=http://localhost:80/api/v1

# 1. Register a host and a driver
HOST_TOKEN=$(curl -s -X POST $API/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"email":"host@example.com","password":"secret-password-1","firstName":"Host","lastName":"Example"}' \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["accessToken"])')

# 2. Host creates a space, shares it for 2 hours (use the UI for the wizard,
#    or POST /api/v1/spaces per the API shapes in the README)

# 3. Search as a driver, reserve, then open the reservation detail:
#    you should see the countdown, and after departure the reservation
#    completes via the scheduler.
```

For a full click-through, open the frontend in a browser and walk the real
flows: register → create space → Share My Spot → Explore/search → Reserve →
active countdown (with the 15-minute banner) → My Reservations → cancel.

## 4. Database: migrations and backups

- **Migrations** run automatically on API boot via Flyway (`V1`–`V5` in
  `backend/src/main/resources/db/migration`). Never edit an applied
  migration; add a new one.
- **Backups:** the database lives in the `spotshare-pgdata-prod` Docker
  volume. Back it up regularly:

```bash
# Logical backup (nightly cron on the host is enough for this scale)
docker exec spotshare-db-prod pg_dump -U spotshare spotshare \
  | gzip > spotshare-$(date +%F).sql.gz

# Restore
gunzip -c spotshare-2026-09-24.sql.gz \
  | docker exec -i spotshare-db-prod psql -U spotshare -d spotshare
```

  Keep off-host copies. Test a restore at least once before you depend on it.

## 5. Logs and operations

```bash
docker compose -f docker-compose.prod.yml logs -f api   # app logs (WARN+)
docker compose -f docker-compose.prod.yml logs -f web
```

- Access logs are minimal and never include request bodies.
- The two `@Scheduled` jobs log at INFO when they act; silence is normal.
- To update: `git pull`, then `docker compose -f docker-compose.prod.yml up -d --build`. Flyway migrates on boot; the old container is replaced only after the new one is healthy (depends_on ordering).

## Security checklist (verified in the codebase)

- [ ] `JWT_SECRET` is a long random value (≥ 32 chars); prod refuses to boot otherwise.
- [ ] `APP_CORS_ALLOWED_ORIGINS` lists only your real frontend origin(s).
- [ ] TLS terminates in front of the `web` service (the compose file is HTTP).
- [ ] bcrypt cost is 12; passwords never logged.
- [ ] Photo uploads: JPEG/PNG/WebP only, 5 MB per photo, 8 per space.
- [ ] Rate limits: auth (20/min/IP), search and geocode (per-endpoint limits).
- [ ] Errors return `{code,message,correlationId}` — `INTERNAL_ERROR` never leaks internals; prod disables Spring's error message inclusion.
- [ ] The API runs as a non-root user inside its container; the DB is not published to the host in prod compose.

## Honest limitations

- This guide and the compose files have **not** been executed end-to-end in
  the development sandbox (no Docker there, and JVM-to-DB TCP is blocked),
  so treat the first real deploy as a verification run and report issues.
- The in-memory rate limiter is per-container; it fits a single-box
  deployment and would need a shared store if you ever scale the API
  horizontally.
- Geocoding depends on Nominatim's public service; for heavy use, run your
  own Nominatim instance or a commercial geocoder and set
  `NOMINATIM_BASE_URL`.
