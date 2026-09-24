package com.spotshare.availability;

/**
 * Where an availability window came from. Phase 3 creates MANUAL windows only
 * (the host's "I'm leaving" share); COMMUTE and VACATION windows arrive with
 * Phases 8 and 9 and share the same table.
 */
public enum WindowSource {
    MANUAL,
    COMMUTE,
    VACATION
}
