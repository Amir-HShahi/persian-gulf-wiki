package com.persiangulfwiki.core.moderation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;

public record ModerationDashboardSummaryResponse(
        @Schema(description = "Today's date in Tehran time; every count below is for this day.")
        LocalDate date,
        @Schema(description = "Article revisions first submitted today that are still waiting for review "
                + "(including ones sent back for changes).")
        DashboardStatResponse pending,
        @Schema(description = "Article revisions approved today.")
        DashboardStatResponse approved,
        @Schema(description = "Article revisions rejected today.")
        DashboardStatResponse rejected) {
}
