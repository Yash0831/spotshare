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

**Phase 9 — Vacation mode, implemented and tested.** What works today:

Everything from Phase 8, plus the host's whole-trip share:

- **Vacation mode:** on the space page, the host taps **\"+ Going on vacation?
  Share for the whole trip\"**, picks a start and an end date-time, and a price
  (free or hourly). The form shows a live trip summary (\"3 days 15 hours\").
  The server creates one multi-day `availability_windows` row
  (source=`VACATION`) — e.g. Friday 6:00 PM → Monday 9:00 AM. Validation
  mirrors manual shares: start in the future, end after start, at least
  30 minutes (no maximum — a two-week trip is legitimate), free or
  $0.01–$100/hr, and no overlap with existing shares (adjacent allowed)
- **Same rules as everything else:** drivers reserve sub-periods inside the
  vacation window with the usual containment rule — multiple reservations can
  cover different parts of one long window. The live share shows an \"On
  vacation\" state with an **\"I'm back\"** button: that's the existing
  return-early path, so a parked driver is never silently cut off (a blocked
  return answers `RETURN_BLOCKED_BY_RESERVATION` with the exact
  `earliestReturnTime`)
- API: `POST /api/v1/spaces/{id}/vacation` (201) with
  `{startDateTime, endDateTime, hourlyRateCents}`
- **No schema changes:** the V3 `source` check constraint already accepted
  `VACATION` — vacation windows are ordinary windows from day one

**Phase 8 — Commute mode, implemented and tested.** Before that:

Everything from Phase 7, plus the host's weekly pattern:

- **Commute mode:** on the space page, the host taps **\"+ Add a weekly
  pattern\"**, picks weekdays (Mon–Sun chips), a start/end time, and a price
  (free or hourly). One entry per weekday — the server rejects a duplicate
  with a friendly 409 `SCHEDULE_EXISTS`. Windows materialize **immediately**:
  real `availability_windows` (source=`COMMUTE`) for the next 14 days, so the
  host sees the pattern take effect right away
- **Local times, done right:** start/end are times-of-day in the schedule's
  IANA timezone (the host's device timezone, sent by the app), so a 9:00 AM
  share stays 9:00 AM local across daylight-saving changes. Validation mirrors
  manual shares: end after start, at least 30 minutes, free or $0.01–$100/hr
- **Materializer:** an hourly `@Scheduled` sweep rolls every active schedule
  14 days out. It's idempotent — the same (space, period, `COMMUTE`) window is
  skipped when present, with the natural unique key on
  `(space_id, starts_at, ends_at, source)` as the backstop. A manual share
  always wins: the materializer never creates an overlapping window
- **Pause/resume:** pausing stops future materialization and removes future
  unreserved `COMMUTE` windows; windows with a confirmed reservation are kept
  (a driver is never silently cancelled) and live windows expire on their own.
  Resume re-materializes immediately. Deleting a schedule cleans up the same way
- **Same rules as everything else:** commute windows are ordinary windows —
  search shows them (marked \"Auto · Commute\" on the host's share list),
  drivers reserve them, return-early applies to them, and the booking /
  exclusion-constraint rules hold unchanged
- API: `POST /api/v1/spaces/{id}/commute-schedules` (201),
  `GET /api/v1/spaces/{id}/commute-schedules`,
  `DELETE /api/v1/commute-schedules/{scheduleId}` (204),
  `POST /api/v1/commute-schedules/{scheduleId}/pause`,
  `POST /api/v1/commute-schedules/{scheduleId}/resume`

**Phase 7 — Return early, implemented and tested.** Before that:

Everything from Phase 6, plus the host returning earlier than planned:

- **Return early:** on a live share (the space page and My Parking's
  "Currently sharing"), the host taps **"I'm back early"** and picks a new
  return time — Now, +15 min, +30 min, +1 hr, or a custom time. The share
  window simply shrinks; the sharing UI updates immediately (an ended share
  drops off the list)
- **Reservation protection (spec §7):** a confirmed reservation is never
  silently cancelled or shortened. If a driver is parked past the requested
  return, the API answers 422 `RETURN_BLOCKED_BY_RESERVATION` —
  "Your space is reserved until 7:30 PM. Earliest available return:
  7:30 PM." — and carries the exact `earliestReturnTime` in the error
  `details`, so the dialog stays open and shows when the host may return.
  Ending exactly when a reservation ends is allowed (half-open
  `[arrival, departure)`); cancelled reservations don't block
- Validation: the new return must be in the future (a 60-second grace
  covers clock skew on the "Now" tap), strictly earlier than the current
  end, and keep the window's 30-minute minimum. An already-ended share is a
  no-op success. Only the owning host can do this (403 otherwise)
- API: `POST /api/v1/availability/{windowId}/return-early` with
  `{newReturnTime}` returns the updated window; the `live` flag and
  RETURNING display heuristic re-derive from the timestamps as before

**Phase 6 — Active parking, implemented and tested.** Before that:

Everything from Phase 5, plus the parked experience:

- **Active parking screen:** when a reservation is live
  (`CONFIRMED` and `arrival <= now < departure`), the reservation detail
  shows a prominent "YOU'RE PARKED" card with a live countdown
  ("1 hr 42 min remaining, leave by 10:30 PM"), the exact address, space
  number, instructions, code, and a "Get directions" link (external maps
  URL — no routing algorithm). An in-app banner appears during the last
  15 minutes ("15 minutes left — please head back to your car."). In-app
  only — V1 has no push notifications or SMS, so this countdown and its
  banner are the reminder
- **Completion:** a simple `@Scheduled` job (every 60 s, testable via the
  shared `Clock`) marks `CONFIRMED` reservations with
  `departure <= now` as `COMPLETED`. The bulk update filters on
  `CONFIRMED`, so a driver or host cancellation is never overwritten
- **Host cancellation:** the host can cancel a reservation on their space
  before arrival (`POST /api/v1/reservations/{id}/cancel`) — recorded as
  host-cancelled (`cancelledBy: HOST`), never silent. Cancelling twice is
  a no-op success for both driver and host
- **Host arrivals:** `GET /api/v1/spaces/{id}/reservations?date=YYYY-MM-DD`
  (UTC day; omitted means today) lists that day's reservations for the
  host's space — driver as first name + last initial only, arrival,
  departure, code, status. Non-hosts get 403; drivers are never identified
  further
- **My Reservations** now has three sections driven by the server filters
  (`?filter=active|upcoming|past`): Active (with a live mini countdown),
  Upcoming (cancel with a confirm step), Past. A host-cancelled row reads
  "Cancelled by host" — honest about who cancelled
- React: `useCountdown` hook (1 s tick, self-correcting from `Date.now()`,
  15-minute threshold), `ActiveParkingBanner` component, "Today's
  arrivals" on the host's space page with two-tap host cancel
- Reservations:
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
(148 tests: unit + MockMvc + web slices, including the completion-scheduler
and host-cancel/arrivals tests); the frontend suite is green (88 tests,
including the countdown hook, the 15-minute threshold, the active-parking
banner, and the filtered reservation sections). The database-backed
integration tests run where a database is reachable from the JVM — they
abort cleanly in sandboxes that block JVM database connections. Docker
Compose cannot run in this sandbox, so live boot and the curl walkthroughs
have not been executed here; run them wherever you have Docker/a local
Postgres. No visual map verification was performed in this sandbox.

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

## Manual active-parking check (curl)

```bash
# The completion scheduler runs every 60 s: any CONFIRMED reservation with
# departure <= now becomes COMPLETED automatically (no endpoint to call).
# Verify by listing with the filters (replace $ACCESS):
curl -s "localhost:8080/api/v1/reservations/mine?filter=active" -H "Authorization: Bearer $ACCESS"
curl -s "localhost:8080/api/v1/reservations/mine?filter=past" -H "Authorization: Bearer $ACCESS"
# → a reservation past its departure shows COMPLETED in the past list
#   (cancelled reservations are never touched by the scheduler)

# Host's arrivals for one space — today (replace $HOST_ACCESS and $SPACE)
curl -s localhost:8080/api/v1/spaces/$SPACE/reservations -H "Authorization: Bearer $HOST_ACCESS"
# → [{code, driverName ("Dan D."), arrival, departure, status, cancelledBy}]
# A driver's token on the same URL gets 403 NOT_YOUR_SPACE — never the data

# A specific day (UTC)
curl -s "localhost:8080/api/v1/spaces/$SPACE/reservations?date=2026-09-24" \
  -H "Authorization: Bearer $HOST_ACCESS"

# Host cancels an upcoming reservation before arrival (replace $RES)
curl -s -X POST localhost:8080/api/v1/reservations/$RES/cancel -H "Authorization: Bearer $HOST_ACCESS"
# → 200 with "status":"CANCELLED","cancelledBy":"HOST"; the driver's
#   My Reservations row reads "Cancelled by host"
# → 422 RESERVATION_STARTED if the reservation already started
```

## Manual return-early check (curl)

```bash
# Shrink a live share (replace $HOST_ACCESS and $WINDOW; new time must be
# in the future, earlier than the current end, and keep 30+ min of window)
curl -s -X POST localhost:8080/api/v1/availability/$WINDOW/return-early \
  -H "Authorization: Bearer $HOST_ACCESS" -H 'Content-Type: application/json' \
  -d '{"newReturnTime":"2026-09-24T22:30:00Z"}'
# → 200 with the updated window (shorter endsAt; "live" re-derived)

# When a driver is parked past the requested return:
# → 422 RETURN_BLOCKED_BY_RESERVATION
#   "Your space is reserved until 7:30 PM. Earliest available return: 7:30 PM."
#   with "details":{"earliestReturnTime":"..."} for the UI
# A past time → 422 INVALID_RETURN_TIME; a time at/after the current end →
# 422 NOT_EARLY_RETURN; under 30 min of window → 422 WINDOW_TOO_SHORT;
# someone else's window → 403 NOT_YOUR_WINDOW; an ended share → 200 no-op.
```

## Manual commute-mode check (curl)

```bash
# Add a weekly entry: every Monday 09:00–17:00 America/Chicago, $3/hr
# (dayOfWeek 0 = Monday .. 6 = Sunday; times are local in `timezone`)
curl -s -X POST localhost:8080/api/v1/spaces/$SPACE/commute-schedules \
  -H "Authorization: Bearer $HOST_ACCESS" -H 'Content-Type: application/json' \
  -d '{"dayOfWeek":0,"startTime":"09:00","endTime":"17:00","hourlyRateCents":300,"timezone":"America/Chicago"}'
# → 201 with the schedule; the next two Mondays appear immediately as
# COMMUTE availability windows (14:00Z–22:00Z while Chicago is on CDT)

# List the space's weekly entries (Monday first)
curl -s localhost:8080/api/v1/spaces/$SPACE/commute-schedules \
  -H "Authorization: Bearer $HOST_ACCESS"

# Pause: stops materialization, removes future unreserved COMMUTE windows
# (windows with a confirmed reservation are kept)
curl -s -X POST localhost:8080/api/v1/commute-schedules/$SCHED/pause \
  -H "Authorization: Bearer $HOST_ACCESS"
# → 200 with "active":false; resume works the same way

# Duplicate weekday → 409 SCHEDULE_EXISTS
# ("You already have a commute entry for Monday. ...");
# end before start → 422 INVALID_SCHEDULE; under 30 min → 422 SCHEDULE_TOO_SHORT;
# bad rate → 422 INVALID_PRICE; bad zone → 422 INVALID_TIMEZONE;
# someone else's space → 403 NOT_YOUR_SPACE; deactivated space → 410 SPACE_INACTIVE.
```

## Manual vacation-mode check (curl)

```bash
# Share the whole trip: Friday 6 PM → Monday 9 AM, $3/hr
# (both datetimes are ISO instants; hourlyRateCents null = free)
curl -s -X POST localhost:8080/api/v1/spaces/$SPACE/vacation \
  -H "Authorization: Bearer $HOST_ACCESS" -H 'Content-Type: application/json' \
  -d '{"startDateTime":"2026-09-25T18:00:00Z","endDateTime":"2026-09-28T09:00:00Z","hourlyRateCents":300}'
# → 201 with the window ("source":"VACATION")

# A driver reserves a sub-period inside the vacation window — the usual booking
# rules apply (see "Manual reservation check"); multiple reservations can
# cover different parts of the same long window.

# End the vacation early: the return-early endpoint shrinks the window.
# A parked driver blocks it with 422 RETURN_BLOCKED_BY_RESERVATION
# ("Earliest available return: ..." + details.earliestReturnTime) — the driver
# is never silently cut off.
curl -s -X POST localhost:8080/api/v1/availability/$WINDOW/return-early \
  -H "Authorization: Bearer $HOST_ACCESS" -H 'Content-Type: application/json' \
  -d '{"newReturnTime":"2026-09-26T12:00:00Z"}'

# Start in the past → 422 INVALID_START_TIME; end before start → 422
# INVALID_END_TIME; under 30 min → 422 WINDOW_TOO_SHORT; bad rate → 422
# INVALID_PRICE; overlap with an existing share → 409 OVERLAPPING_WINDOW;
# someone else's space → 403 NOT_YOUR_SPACE; deactivated space → 410
# SPACE_INACTIVE.
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
