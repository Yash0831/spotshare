-- V2: SEED DATA — fictional.
-- Demo accounts (host@demo.example, driver@demo.example, admin@demo.example,
-- all with BCrypt cost-10 hash of 'password123') plus fictional host users and
-- 12 fictional parking spaces across nine US cities. Street addresses, names,
-- and coordinates are invented (coordinates are plausible for the named
-- neighborhoods, not real properties). Availability windows run from the
-- seed execution time through +14 days (8 AM–8 PM each day).

-- ---------------------------------------------------------------------------
-- users
-- ---------------------------------------------------------------------------
INSERT INTO users (id, name, email, password_hash, role) VALUES
    (gen_random_uuid(), 'Demo Host',   'host@demo.example',   '$2a$10$JhQviE6cwnVQDZ9m.YNJtuvIaEOcqcA2gWM9CdXPbUMhicGiuhxHW', 'USER'),
    (gen_random_uuid(), 'Demo Driver', 'driver@demo.example', '$2a$10$JhQviE6cwnVQDZ9m.YNJtuvIaEOcqcA2gWM9CdXPbUMhicGiuhxHW', 'USER'),
    (gen_random_uuid(), 'Demo Admin',  'admin@demo.example',  '$2a$10$JhQviE6cwnVQDZ9m.YNJtuvIaEOcqcA2gWM9CdXPbUMhicGiuhxHW', 'ADMIN'),
    (gen_random_uuid(), 'Priya Raman',   'priya.raman@example.com',   '$2a$10$JhQviE6cwnVQDZ9m.YNJtuvIaEOcqcA2gWM9CdXPbUMhicGiuhxHW', 'USER'),
    (gen_random_uuid(), 'Elena Vasquez', 'elena.vasquez@example.com', '$2a$10$JhQviE6cwnVQDZ9m.YNJtuvIaEOcqcA2gWM9CdXPbUMhicGiuhxHW', 'USER'),
    (gen_random_uuid(), 'Tom Okafor',    'tom.okafor@example.com',    '$2a$10$JhQviE6cwnVQDZ9m.YNJtuvIaEOcqcA2gWM9CdXPbUMhicGiuhxHW', 'USER');

-- ---------------------------------------------------------------------------
-- parking_spaces (fictional listings; authorization confirmed by the host)
-- ---------------------------------------------------------------------------
WITH hosts AS (
    SELECT email, id FROM users
)
INSERT INTO parking_spaces
    (id, host_id, address, city, state, zip_code, latitude, longitude,
     display_area, space_type, description, hourly_price,
     max_vehicle_size, height_restriction, instructions,
     active, authorization_confirmed, authorization_confirmed_at)
VALUES
    -- New York City — Astoria, Queens (driveway)
    (gen_random_uuid(),
     (SELECT id FROM hosts WHERE email = 'priya.raman@example.com'),
     '31-28 38th Street', 'Astoria', 'NY', '11103', 40.7644, -73.9260,
     'Astoria area', 'DRIVEWAY',
     $$Wide private driveway behind a two-family home, two blocks from the 30th Ave subway stop. Easy pull-in, no street maneuvering needed.$$,
     6.50, 'Sedan, SUV', '9 ft clearance',
     $$Enter through the side gate on 38th Street. Park in the right-hand bay marked with the blue cone. Text the host if the gate is latched.$$,
     true, true, now() - interval '6 days'),
    -- New York City — Williamsburg, Brooklyn (garage)
    (gen_random_uuid(),
     (SELECT id FROM hosts WHERE email = 'priya.raman@example.com'),
     '412 Bedford Avenue', 'Brooklyn', 'NY', '11211', 40.7175, -73.9622,
     'Williamsburg area', 'GARAGE',
     $$Secure single-car garage bay under a converted warehouse. Well lit, camera monitored, five minutes from the Bedford L stop.$$,
     9.00, 'Sedan', '7 ft clearance',
     $$Use the keypad on the roll-up door; the code arrives with your reservation confirmation. Park nose-in on the left bay.$$,
     true, true, now() - interval '6 days'),
    -- Chicago — West Loop (garage)
    (gen_random_uuid(),
     (SELECT id FROM hosts WHERE email = 'elena.vasquez@example.com'),
     '113 North Green Street', 'Chicago', 'IL', '60607', 41.8825, -87.6668,
     'West Loop area', 'GARAGE',
     $$Heated indoor garage spot in a West Loop loft building, steps from Restaurant Row. Attendant on site during business hours.$$,
     8.50, 'Sedan, SUV', '6''6" clearance',
     $$Take a ticket at the garage entrance and keep it; show your reservation confirmation to the attendant on level P1.$$,
     true, true, now() - interval '5 days'),
    -- Chicago — Logan Square (driveway)
    (gen_random_uuid(),
     (SELECT id FROM hosts WHERE email = 'elena.vasquez@example.com'),
     '2545 West Logan Boulevard', 'Chicago', 'IL', '60647', 41.9291, -87.7113,
     'Logan Square area', 'DRIVEWAY',
     $$Gravel parking pad beside a brick bungalow on the boulevard. Roomy enough for full-size SUVs and work vans.$$,
     5.75, 'SUV, pickup truck', 'No height restriction',
     $$Pull into the pad off the alley behind the house. Do not block the garage door; the marked rectangle is yours.$$,
     true, true, now() - interval '5 days'),
    -- Los Angeles — Silver Lake (assigned space)
    (gen_random_uuid(),
     (SELECT id FROM hosts WHERE email = 'elena.vasquez@example.com'),
     '3820 Sunset Boulevard', 'Los Angeles', 'CA', '90026', 34.0880, -118.2768,
     'Silver Lake area', 'ASSIGNED_SPACE',
     $$Deeded parking space in a gated Silver Lake condo complex, numbered and reserved. Sunset Junction shops a short walk away.$$,
     7.25, 'Sedan, SUV', '8 ft clearance',
     $$Call the host from the gate intercom on arrival; you will be buzzed in. Space 24 is marked with a yellow curb.$$,
     true, true, now() - interval '4 days'),
    -- Los Angeles — Culver City (EV charging space)
    (gen_random_uuid(),
     (SELECT id FROM hosts WHERE email = 'elena.vasquez@example.com'),
     '9411 Washington Boulevard', 'Culver City', 'CA', '90232', 33.9981, -118.3957,
     'Culver City area', 'EV_SPACE',
     $$Dedicated EV charging spot (Level 2, J1772) in a private Culver City office lot. Electricity included in the hourly rate.$$,
     6.00, 'Sedan, SUV', 'No height restriction',
     $$Bring your own J1772 adapter if your car needs one. Plug in on arrival; the charger releases when you unplug.$$,
     true, true, now() - interval '4 days'),
    -- San Francisco — Mission District (parking lot)
    (gen_random_uuid(),
     (SELECT id FROM hosts WHERE email = 'tom.okafor@example.com'),
     '2849 22nd Street', 'San Francisco', 'CA', '94110', 37.7551, -122.4211,
     'Mission District area', 'PARKING_LOT',
     $$Fenced private lot space in the heart of the Mission, two blocks from 24th St BART. Paved, striped, and lit overnight.$$,
     11.00, 'Sedan, SUV', 'No height restriction',
     $$Enter with the gate code from your confirmation. Park in any spot numbered 40 through 49.$$,
     true, true, now() - interval '3 days'),
    -- Boston — South End (garage)
    (gen_random_uuid(),
     (SELECT id FROM hosts WHERE email = 'tom.okafor@example.com'),
     '527 Columbus Avenue', 'Boston', 'MA', '02118', 42.3402, -71.0800,
     'South End area', 'GARAGE',
     $$Underground garage bay in a South End brownstone condo building. Snow-melted ramps in winter, elevator to the street.$$,
     10.50, 'Sedan', '6''8" clearance',
     $$The garage door opens with the fob code in your confirmation. Descend to level B1; your bay is the third on the right.$$,
     true, true, now() - interval '3 days'),
    -- Washington DC — Capitol Hill (driveway)
    (gen_random_uuid(),
     (SELECT id FROM hosts WHERE email = 'tom.okafor@example.com'),
     '614 8th Street SE', 'Washington', 'DC', '20003', 38.8799, -76.9951,
     'Capitol Hill area', 'DRIVEWAY',
     $$Brick-paved driveway pad behind a Capitol Hill rowhouse, a ten-minute walk to the Eastern Market metro. Quiet block.$$,
     7.75, 'Sedan, SUV', 'No height restriction',
     $$Enter via the rear alley off 7th Street SE. The pad is the second from the corner, marked with white pavers.$$,
     true, true, now() - interval '2 days'),
    -- Dallas — Uptown (parking lot)
    (gen_random_uuid(),
     (SELECT id FROM hosts WHERE email = 'tom.okafor@example.com'),
     '2511 McKinney Avenue', 'Dallas', 'TX', '75201', 32.7919, -96.8009,
     'Uptown area', 'PARKING_LOT',
     $$Shaded surface-lot space in Uptown Dallas near the Katy Trail trailhead. Open-air, security patrols in the evening.$$,
     5.50, 'Sedan, SUV, pickup truck', 'No height restriction',
     $$Park in any unmarked visitor space along the north fence. Display the dashboard placard code from your confirmation.$$,
     true, true, now() - interval '2 days'),
    -- Miami — Wynwood (assigned space)
    (gen_random_uuid(),
     (SELECT id FROM hosts WHERE email = 'tom.okafor@example.com'),
     '2417 NW 2nd Avenue', 'Miami', 'FL', '33127', 25.8013, -80.1996,
     'Wynwood area', 'ASSIGNED_SPACE',
     $$Numbered space in a gated Wynwood arts-district lot, surrounded by murals. Covered by a shade canopy.$$,
     8.25, 'Sedan, SUV', '9 ft clearance',
     $$Gate code is in your confirmation. Space 12 sits under the canopy, painted with the teal stripe.$$,
     true, true, now() - interval '1 day'),
    -- Seattle — Capitol Hill (EV charging space)
    (gen_random_uuid(),
     (SELECT id FROM hosts WHERE email = 'tom.okafor@example.com'),
     '415 15th Avenue E', 'Seattle', 'WA', '98112', 47.6225, -122.3124,
     'Capitol Hill area', 'EV_SPACE',
     $$EV charging spot (Level 2, J1772 and Tesla) in a Capitol Hill apartment garage. Volunteer Park is two blocks north.$$,
     6.75, 'Sedan, SUV', '7 ft clearance',
     $$Follow the signs to the EV row on level P2. Scan the QR code on the charger to start your session.$$,
     true, true, now() - interval '1 day');

-- ---------------------------------------------------------------------------
-- availability_windows: every active space, 8 AM–8 PM each day, from the seed
-- execution time through +14 days (relative to now(), so windows are always
-- current when the seed runs).
-- ---------------------------------------------------------------------------
INSERT INTO availability_windows (id, space_id, starts_at, ends_at)
SELECT gen_random_uuid(),
       s.id,
       d.day_start + interval '8 hours',
       d.day_start + interval '20 hours'
FROM parking_spaces s
CROSS JOIN LATERAL (
    SELECT date_trunc('day', now()) + (g.d || ' days')::interval AS day_start
    FROM generate_series(0, 13) AS g(d)
) d
WHERE s.active = true;
