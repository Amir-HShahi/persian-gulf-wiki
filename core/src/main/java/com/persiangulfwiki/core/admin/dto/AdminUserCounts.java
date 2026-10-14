package com.persiangulfwiki.core.admin.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "How many users each filter matches, across all users; the active filter doesn't change these.")
public record AdminUserCounts(
        long all,
        @Schema(description = "Suspended or banned users.") long blocked,
        long admin,
        long moderator,
        long expertReviewer,
        @Schema(description = "Users with no role besides CONTRIBUTOR.") long contributor) {
}
