package com.persiangulfwiki.core.moderation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.util.List;

public record ModerationDashboardArticlesResponse(
        DashboardRange range,
        @Schema(description = "First day of the range (Tehran time), inclusive.")
        LocalDate from,
        @Schema(description = "Last day of the range (Tehran time), inclusive; always today.")
        LocalDate to,
        @Schema(description = "Article revisions approved on each day, oldest first, one entry for every day from "
                + "`from` to `to`. A day with no approvals has count 0.")
        List<DailyCountResponse> approvedPerDay,
        @Schema(description = "Article revisions first submitted within the range, grouped by where they stand now.")
        StatusBreakdownResponse statusBreakdown) {
}
