package com.persiangulfwiki.core.marinedata.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;

public record MarineSeriesPointResponse(
        @Schema(description = "Start of the bucket (UTC): the top of the hour for hourly points, midnight UTC for daily ones.",
                example = "2026-10-10T00:00:00Z")
        Instant bucketStart,

        @Schema(description = "The bucket's value: the average of its readings, or the maximum for stress indicators "
                + "(`coral_bleaching_alert`, `degree_heating_weeks`).", example = "31.42")
        BigDecimal value) {
}
