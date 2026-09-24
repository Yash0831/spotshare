# SpotShare V1 Product Specification

Status: DRAFT — awaiting review. No code changes until the user says **START PHASE 1**.

Note: two commits (`1fb6a8f`, `bc708a9`) were pushed to `main` before this spec
was written, under a superseded plan. Phase 1 re-verifies and rebuilds the
foundation per this spec (Java 21, phase gates). Nothing else is grandfathered in.

---

## 1. Product overview

SpotShare is a marketplace for **temporarily unused, privately controlled parking
spaces**. A host who is leaving taps "Share my spot" with a return time; the space
becomes bookable nearby until they return, then automatically disappears. A driver
near a destination finds it on a map, reserves a time window, parks, and leaves
before the window ends.

Core loop: **I'M LEAVING → SHARE → DISCOVER → RESERVE → PARK → RETURN.**

Works anywhere in the United States. Nothing in code or behavior is tied to any
city. Optimized for real people using it from a phone, standing next to a car —
not for impressing recruiters.

## 2. V1 scope

**Host**
- Create a parking space once (5-step quick setup: location → type → photos →
  instructions → authorization confirmation).
- "Share my spot": pick return time + price (free or hourly), one tap, live in
  under a minute. Space auto-expires at return time.
- Return early with reservation protection (see §7).
- Commute mode: weekly recurring availability, pausable.
- Vacation mode: date-range availability.
- See today's reservations and upcoming arrivals; temporarily disable listing.

**Driver**
- Park Now: use current location or a typed destination → nearby available spaces.
- Plan Parking: destination + date + arrival + departure → spaces available for the
  **entire** requested period.
- Map + list results with approximate location, distance, price, type, photos.
- Reserve with a price breakdown → reservation code (e.g. `SP-K84D2`) → exact
  address and instructions revealed after confirmation.
- Active-parking countdown screen ("1 hr 42 min remaining, leave by 10:30 PM").
- Cancel an upcoming reservation.

**Platform**
- Accounts: email + password + first/last name, optional phone. A user can be
  host, driver, or both.
- Geographic search (PostGIS), availability filtering, price/covered/EV/vehicle-size
  filters, adjustable radius.
- Prices displayed everywhere; reservations clearly labeled **"Beta — no payment
  required"** until the payment phase exists.

## 3. Explicitly NOT in V1

Real payments, host payouts, platform fees, refunds, ratings/reviews, push
notifications, SMS, license-plate verification, QR access, gate integrations,
commercial operators, business accounts, event parking, calendar sync, insurance
workflows, identity verification, dynamic/surge/AI pricing, waitlists, native
mobile apps, host–driver messaging, a full admin console, AI/ML/chatbots/
recommendations of any kind.

## 4. Host user journey

1. Signs up, creates space **once** (5 quick steps).
2. Leaving: opens SpotShare → **SHARE MY SPOT** → sets return time (e.g. 5:30 PM)
   and price ($3/hour or free) → confirms. Space is live in under a minute.
3. Sees today's reservations (who, when). Knows at a glance when the space is
   theirs again.
4. Returns (or taps "I'm coming home early" — see §7). Space automatically
   leaves the marketplace at return time. Tomorrow it is simply not shared
   unless the host shares again. Commute mode automates the weekly pattern.

## 5. Driver user journey

1. Opens SpotShare → **PARK NOW** (current location or destination) or **PLAN
   PARKING** (destination + date + arrival/departure).
2. Sees map pins with prices + list sorted by distance (approximate locations).
3. Taps a space → sees availability, price, type, photos, vehicle fit.
4. Picks arrival/departure → price breakdown (5 h × $3 = $15.00) → **RESERVE**.
5. Gets reservation code `SP-K84D2`, exact address, space number, instructions.
6. Parks; countdown screen shows time remaining with an in-app 15-minute
   reminder; leaves by departure time. Space becomes bookable again.

## 6. Main screens

Mobile-first, bottom tab bar: **Explore · Park Now · My Parking · My Reservations ·
Profile**. (Park Now is the prominent center action.)

- **Home** (logged out): brand line, "Where do you need parking?" field,
  PARK NOW button, host CTA ("Have an empty parking spot? Share your spot").
  (Logged in): destination + arrival + departure + FIND PARKING.
- **Search results**: map with price pins + list; distance, availability window,
  hourly price, estimated total, filters (price, covered, EV, vehicle size, radius).
- **Space detail** (privacy-safe): photos, area label, type, price, availability,
  vehicle fit → time picker → price breakdown → RESERVE.
- **Reservation confirmation**: code, exact address, space number, instructions,
  arrival/leave-by times.
- **My Reservations**: upcoming / active (countdown) / past; cancel upcoming.
- **My Parking** (host): spaces, SHARE MY SPOT sheet, today's reservations,
  availability windows, commute/vacation mode, disable toggle.
- **Space setup wizard**: 5 steps (§2).
- **Profile**: name, email, phone; sign out.
- **Auth**: register / login. Simple, no clutter.

## 7. Business rules

- **Authorization to share**: host must check "I confirm that I own, control, or
  have permission to share this parking space." Stored with timestamp on the
  space. Prohibited: public street parking, fire lanes, accessible spaces unless
  legally permitted, spaces the user doesn't control, illegal locations.
- **Space display states are derived, not a state machine**: OFFLINE
  (`active=false`); PRIVATE (active, nothing shared now); AVAILABLE (inside a
  share window, no confirmed reservation covering now); RESERVED (confirmed
  reservation covering now); RETURNING (within 30 min before the current window
  ends — display heuristic).
- **Sharing**: share window = [now, return_time); return_time must be in the
  future; price = 0 (free) or positive hourly rate.
- **Reservation containment**: [arrival, departure) must lie entirely inside a
  **single** availability window. arrival < departure; arrival ≥ now.
- **No self-booking**: a driver cannot reserve their own space.
- **Double booking is impossible** (see §9).
- **Pricing**: billable minutes × (hourly_rate / 60), rounded HALF_UP to cents.
  Free = 0. No dynamic/surge/AI pricing.
- **Cancellation**: driver may cancel any time before arrival (period released,
  idempotent — cancelling twice is a no-op success). Host **cannot**
  delete/shrink availability overlapping a confirmed reservation. Host may cancel
  a reservation before arrival (recorded as host-cancelled).
- **Return early**: host picks a new return time. If a confirmed reservation
  ends after it, reject with: "Your space is reserved until 7:30 PM. Earliest
  available return: 7:30 PM." Never silently cancel a driver's reservation.
- **Reservation codes**: `SP-` + 5 chars from an unambiguous alphabet
  (no 0/O, 1/I). Unique.
- **Completion**: reservations auto-complete at departure (scheduler). Host sees
  past reservations.
- **Friendly errors only**: e.g. "This space was just reserved. Please choose
  another nearby space." — never raw constraint or SQL text. Full mapping in §11.

## 8. Database schema

PostgreSQL + PostGIS. Flyway owns all DDL. UUID primary keys.

**users** — id, email (unique), password_hash (bcrypt), first_name, last_name,
phone (nullable), role (`USER`, `ADMIN`), created_at, updated_at.

**parking_spaces** — id, host_id → users, label ("B17"), address, city,
state (2-char), zip_code, latitude, longitude,
geom `geography(Point,4326)` GENERATED ALWAYS AS
`(ST_SetSRID(ST_MakePoint(longitude, latitude), 4326)::geography)` STORED
(lat/lng kept as plain columns for readability/debugging; geom is the indexed
truth), area_label (public, e.g. "West Loop"), parking_type enum
(`DRIVEWAY, PRIVATE_GARAGE, ASSIGNED_SPACE, PRIVATE_LOT, EV_SPACE, OTHER_PRIVATE`),
description, vehicle_sizes `text[]` (checked against
MOTORCYCLE/SEDAN/SUV/VAN/TRUCK), height_limit_inches (nullable int),
covered bool, ev_charging bool, parking_instructions (private), 
authorization_confirmed bool, authorization_confirmed_at, active bool,
created_at, updated_at. Indexes: GIST(geom), (host_id), (active).

**parking_photos** — id, space_id → parking_spaces, s3_key, url, sort_order,
created_at.

**availability_windows** — id, space_id → parking_spaces, starts_at, ends_at
(timestamptz), source (`MANUAL, COMMUTE, VACATION`), created_at.
Check: ends_at > starts_at. Index: (space_id, starts_at, ends_at).

**recurring_schedules** (commute mode) — id, space_id → parking_spaces,
day_of_week (0–6), start_time/end_time (`time`), hourly_price nullable,
active bool. A nightly job materializes `availability_windows` (source=COMMUTE)
for the next 14 days; unique constraint on
(space_id, starts_at, ends_at, source) prevents duplicates.

**reservations** — id, space_id → parking_spaces, driver_id → users,
arrival/departure (timestamptz),
period `tstzrange` GENERATED ALWAYS AS (`tstzrange(arrival, departure, '[)'`)) STORED,
hourly_rate `numeric(8,2)`, total_price `numeric(10,2)`,
status (`CONFIRMED, COMPLETED, CANCELLED_BY_DRIVER, CANCELLED_BY_HOST`),
code `varchar(8)` unique, idempotency_key `varchar(64)` unique nullable,
cancelled_at nullable, created_at, updated_at.
Check: arrival < departure.
**Anti-double-booking:** `EXCLUDE USING gist (space_id WITH =, period WITH &&)
WHERE (status = 'CONFIRMED')` (requires `btree_gist`).
Indexes: (driver_id, status), (space_id, status), unique(code).

No event-sourcing tables, no audit tables in V1 — keep it to what the product needs.

## 9. Reservation overlap / concurrency strategy

Three layers, cheapest first:

1. **Application check** inside one transaction: containment in an availability
   window, no overlapping CONFIRMED reservation (`period && tstzrange(...)`),
   not own space, valid times.
2. **Per-space advisory lock**: `pg_advisory_xact_lock(hashtext(space_id::text))`
   held for the transaction, so two concurrent requests for the same space
   serialize; different spaces never block each other.
3. **Database backstop**: the EXCLUDE constraint (§8). Anything that slips past
   the app layer fails here; the resulting `exclusion_violation` is translated to
   "This space was just reserved. Please choose another nearby space." (HTTP 409).

Adjacent bookings (2–5 PM then 5–7 PM) are allowed: the range uses `[)` bounds.
**Integration test required**: two threads reserve the same overlapping period
simultaneously → exactly one CONFIRMED, the other gets the friendly 409.

## 10. Geographic search approach

PostGIS is used because geographic search is a genuine product requirement
(explicitly allowed): one extension, correct meter-based radius, KNN ordering —
simpler and less error-prone than hand-rolled haversine SQL.

- Spaces carry `geom geography(Point, 4326)` (§8).
- Search: `ST_DWithin(geom, ST_MakePoint(:lng,:lat)::geography, :radiusMeters)`
  `ORDER BY geom <-> ST_MakePoint(:lng,:lat)::geography`, single query also
  enforcing: `active`, one availability window containing [arrival, departure),
  no conflicting CONFIRMED reservation, and optional filters
  (max price, covered, EV, vehicle size via array overlap).
- Default radius 3 miles, adjustable 0.5–25 miles. Distances displayed in miles,
  1 decimal.
- Geocoding (address → coordinates) and "use my location" (browser geolocation)
  feed the search; provider behind a `GeocodingProvider` interface (Nominatim
  implementation first — the one interface-with-one-implementation allowed,
  because swapping map providers later is a known, genuine need).

## 11. Privacy / security rules

- **Public search/detail DTOs never contain**: exact address, parking/space
  instructions, host email/phone, host last name, driver contact details.
  Public identity is "first name + last initial" (e.g. "Michael R.").
- **Approximate map location**: public coordinates rounded to 3 decimals
  (~110 m) plus the host-entered `area_label` ("West Loop, Chicago, IL").
- **Exact address + instructions** are returned only by
  `GET /reservations/{id}` when the caller is the reservation's driver
  (status CONFIRMED or active) or the space's host.
- Auth: bcrypt(12), JWT access 15 min + rotating refresh tokens, RBAC
  (USER/ADMIN). Users mutate only their own spaces/reservations; drivers see
  only their reservations; hosts see only their spaces' reservations.
- Validation on every input (times, coordinates, addresses, prices).
  Rate-limit geocode + search endpoints.
- Secrets strictly from environment; refuse to boot in prod without JWT secret.
- Error catalog (friendly messages, no internals): space just reserved (409),
  outside availability (422), inactive listing (410/404), invalid/past times
  (422), own-space booking (403), double cancel (200 no-op), return-early
  conflict (409 with earliest return time), unauthorized (401/403), bad
  coordinates/address (422).

## 12. REST API design

Base `/api/v1`. Errors: `{ "code": "SPACE_JUST_RESERVED", "message": "…" }`.

- Auth: `POST /auth/register`, `POST /auth/login`, `POST /auth/refresh`,
  `POST /auth/logout`, `GET /me`.
- Spaces (host): `POST /spaces`, `GET /spaces/mine`, `GET /spaces/{id}`
  (owner view), `PATCH /spaces/{id}`, `POST /spaces/{id}/deactivate`,
  `POST /spaces/{id}/photos` (multipart → S3-compatible; 501 with clear message
  when storage unconfigured), `DELETE /spaces/{id}/photos/{photoId}`.
- Availability: `POST /spaces/{id}/share` {returnTime, hourlyPrice} — the
  one-tap "I'm leaving"; `GET /spaces/{id}/availability`;
  `DELETE /availability/{windowId}` (rejected if a confirmed reservation
  overlaps); `POST /spaces/{id}/return-early` {newReturnTime};
  `PUT /spaces/{id}/commute-schedule`; `POST /spaces/{id}/vacation`
  {startDate, endDate, hourlyPrice}.
- Discovery (public): `GET /spaces/search?lat&lng&arrival&departure
  &radiusMiles&maxPrice&covered&ev&vehicleSize` → privacy-safe DTOs with
  distance + estimated total; `GET /geocode?q=` → candidates.
- Reservations (driver): `POST /reservations` {spaceId, arrival, departure}
  with `Idempotency-Key` header (mobile double-tap safety) → breakdown + code;
  `GET /reservations/mine?filter=upcoming|active|past`;
  `GET /reservations/{id}` (address reveal — authorized only);
  `POST /reservations/{id}/cancel`.
- Host: `GET /spaces/{id}/reservations?date=` (today's arrivals).

## 13. Frontend structure

React 18 + TypeScript + Vite. **One** lightweight UI approach: Tailwind CSS
(utility-first, no heavy component library). Leaflet (`react-leaflet`) +
OpenStreetMap tiles for maps. Typed API client, no codegen.

```
src/
  pages/        Home, SearchResults, SpaceDetail, Reserve, ReservationDetail,
                MyReservations, MyParking, SpaceWizard, ShareSheet, Profile,
                Login, Register
  components/   BottomNav, SpaceCard, MapView, PriceBreakdown, Countdown,
                TimePicker, Filters, EmptyState, ErrorBanner, PhotoUploader
  api/          client.ts (typed fetch wrapper, auth refresh), types.ts
  hooks/        useGeolocation, useCountdown, useAuth
  utils/        money.ts (cents-safe formatting), time.ts
```

Mobile-first: bottom tab nav, large touch targets, one-thumb flows; loading,
empty, and error states on every screen; accessible labels and contrast.

## 14. Backend structure

Modular monolith (single deployable). Packages by business capability:

```
com.spotshare
  auth/          login/register/JWT/refresh
  user/          profile
  parking/       spaces + photos
  availability/  windows, share/return-early, commute materializer, vacation
  reservation/   booking, cancellation, completion scheduler, codes
  search/        nearby search, GeocodingProvider (+ Nominatim impl)
  common/        config, security, error catalog, Clock bean, validation
```

No interfaces with a single implementation except `GeocodingProvider`
(justified in §10). `java.time.Clock` bean everywhere time matters (testable
expiry/countdown). Flyway migrations own the schema.

## 15. Local development setup

- `docker compose up` → PostGIS database only (`postgis/postgis:15`).
- Backend: `cd backend && mvn spring-boot:run` → `http://localhost:8080`.
- Frontend: `cd frontend && npm install && npm run dev` → `http://localhost:5173`.
- `.env.example` documents: DB credentials, `JWT_SECRET`, S3-compatible storage
  (optional — photo upload returns 501 without it), Nominatim `User-Agent`
  contact, `APP_CORS_ALLOWED_ORIGINS`.
- Seed: `V10__seed_demo.sql` — fictional hosts/spaces in **New York City,
  Chicago, Dallas** (seed data only; zero city-specific behavior in code).
  Demo logins on the login screen: host + driver (`password123`).

## 16. Phase-by-phase implementation plan

Each phase: plan → implement → run → test → debug → clean up → explain → **STOP**.
A phase is done only when backend, frontend, DB, validation, authorization,
errors, tests, and the real user workflow all work. Deployment work happens
**only** in Phase 11.

- **Phase 1 — Foundation**: backend + frontend projects, PostGIS via Docker
  Compose, Flyway, auth (register/login/JWT/refresh), basic accounts. Run
  everything, test everything. STOP.
- **Phase 2 — Parking listing**: space creation (5-step wizard), coordinates,
  photos, authorization confirmation, My Parking. STOP.
- **Phase 3 — Temporary availability**: Share My Spot (return time + price),
  auto-expiry, display states, return-early basics. STOP.
- **Phase 4 — Discovery**: destination search, geocode, map + list, nearby
  filtering, Park Now. STOP.
- **Phase 5 — Reservations**: booking workflow, price calc, conflict detection,
  exclusion constraint + advisory lock, reservation codes, address reveal.
  Concurrency integration test. STOP.
- **Phase 6 — Active parking**: countdown screen, in-app 15-min reminder,
  upcoming reservations, host arrivals view, cancellation. STOP.
- **Phase 7 — Return early**: new-return validation, earliest-return
  calculation, reservation protection. STOP.
- **Phase 8 — Commute mode**: recurring schedules, materializer job, pause.
  STOP.
- **Phase 9 — Vacation mode**: multi-day availability. STOP.
- **Phase 10 — Product polish**: mobile UX pass, loading/empty/error states,
  accessibility, responsive, performance basics. STOP.
- **Phase 11 — Deployment**: production config, env vars, security checks,
  deployment documentation. Only then.
