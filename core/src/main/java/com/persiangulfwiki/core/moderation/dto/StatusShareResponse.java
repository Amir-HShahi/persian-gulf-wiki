package com.persiangulfwiki.core.moderation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record StatusShareResponse(
        long count,
        @Schema(description = "Share of the three statuses' total, in percent with one decimal. The three always add "
                + "up to exactly 100, or are all 0 when there is nothing in the range.")
        double percent) {
}
