package com.spotshare.availability;

/**
 * The user-facing state of a parking space. States are <em>derived</em> from
 * the space's active flag, its availability windows, and reservations — there
 * is no state machine and no persisted status column (see docs/v1-spec.md §7).
 *
 * <ul>
 *   <li>OFFLINE — the space is deactivated ({@code active=false}).</li>
 *   <li>PRIVATE — active, but nothing is shared right now.</li>
 *   <li>AVAILABLE — inside a share window and bookable.</li>
 *   <li>RESERVED — a confirmed reservation covers now (Phase 5).</li>
 *   <li>RETURNING — within 30 minutes of the current window's end
 *       (display heuristic).</li>
 * </ul>
 */
public enum DisplayState {
    OFFLINE,
    PRIVATE,
    AVAILABLE,
    RESERVED,
    RETURNING
}
