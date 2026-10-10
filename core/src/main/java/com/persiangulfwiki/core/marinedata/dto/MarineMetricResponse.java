package com.persiangulfwiki.core.marinedata.dto;

import com.persiangulfwiki.core.marinedata.entity.MarineStatus;

import io.swagger.v3.oas.annotations.media.Schema;

import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

// One homepage card: the latest value of one metric at one station, with everything the card
// draws so it needs no second call.
public record MarineMetricResponse(
        @Schema(description = "Stable metric key; labels are the client's job.", example = "sea_surface_temperature")
        String metricKey,

        @Schema(description = "Stable station key; display names are the client's job.", example = "bushehr")
        String stationKey,

        @Schema(description = "The latest reading.", example = "31.6")
        BigDecimal value,

        @Schema(description = "Stable unit key: celsius, celsius_weeks, level (0-4), index, or ug_m3 (micrograms per "
                + "cubic metre).", example = "celsius")
        String unit,

        @Schema(description = "The time the value describes (UTC) — the hour for hourly metrics, 12:00 UTC of the day "
                + "for daily ones. Daily NOAA metrics usually lag by one or more days.", example = "2026-10-11T09:00:00Z")
        Instant observedAt,

        @Schema(description = "Latest value minus the average of the 7 days before it. Absent when there is no earlier "
                + "data to compare against.", nullable = true, example = "0.8")
        @Nullable BigDecimal baselineDelta,

        @Schema(description = "Status band for metrics that have one (`coral_bleaching_alert`, `air_quality_index`, "
                + "`sea_surface_temperature_anomaly`); absent for the rest.", nullable = true)
        @Nullable MarineStatus status,

        @Schema(description = "Up to 7 daily points ending on the day of `observedAt`, oldest first.")
        List<MarineSeriesPointResponse> sparkline,

        @Schema(description = "When the provider last answered successfully for this station (UTC). Absent if it never has.",
                nullable = true, example = "2026-10-11T09:05:00Z")
        @Nullable Instant lastSyncedAt,

        @Schema(description = "After this moment the data should be shown as stale. Compare it with the current time on "
                + "the client; the server does not send a stale flag because a cached flag would stay wrong while a "
                + "provider is down. Absent together with `lastSyncedAt`.", nullable = true,
                example = "2026-10-11T11:05:00Z")
        @Nullable Instant staleAfter,

        @Schema(description = "Data provider to credit on the card.", example = "Open-Meteo")
        String sourceName,

        @Schema(description = "The provider's licence, to show next to the credit.", example = "CC BY 4.0")
        String sourceLicense) {

    public MarineMetricResponse {
        sparkline = List.copyOf(sparkline);
    }
}
