/**
 * Reservations. Double booking is made impossible at the database level via a
 * PostgreSQL EXCLUDE constraint (btree_gist) on (space_id WITH =,
 * reserved_period WITH &&) for CONFIRMED rows; application-level checks are
 * belt and suspenders. (Phase 2: entity + constraint; Phase 5: reserve/cancel engine.)
 */
package com.spotshare.reservation;
