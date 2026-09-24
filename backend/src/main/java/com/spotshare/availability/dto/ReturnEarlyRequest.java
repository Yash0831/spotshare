package com.spotshare.availability.dto;

import java.time.OffsetDateTime;

import jakarta.validation.constraints.NotNull;

/** The host's new return time for a return-early (Phase 7). */
public record ReturnEarlyRequest(
        @NotNull(message = "Choose a new return time.") OffsetDateTime newReturnTime) {
}
