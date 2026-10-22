package com.persiangulfwiki.core.dev.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;

// Readings carry their own observedAt, so any history shape (a full week for a sparkline, a
// single reading days old) is one call. lastSyncedAt defaults to now; set it in the past to
// mint a station whose card is already stale.
public record DevTestMarineReadingRequest(
        @NotBlank String metricKey,
        @NotEmpty @Size(max = 2000) List<@Valid DevTestMarineReadingPointRequest> readings,
        @Nullable Instant lastSyncedAt) {
}
