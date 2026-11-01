package com.persiangulfwiki.core.moderation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import org.jspecify.annotations.Nullable;

public record DashboardStatResponse(
        @Schema(description = "How many fall on today (Tehran time).")
        long count,
        @Schema(nullable = true, description = "Change from yesterday's count in percent, rounded to one decimal; negative means a drop. "
                + "Absent (null) when yesterday's count was 0, since no percentage can be computed.")
        @Nullable Double changePercent) {
}
