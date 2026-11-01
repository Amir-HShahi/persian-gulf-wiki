package com.persiangulfwiki.core.moderation.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record StatusBreakdownResponse(
        StatusShareResponse approved,
        @Schema(description = "Still waiting for review, including revisions sent back to their author for changes.")
        StatusShareResponse pending,
        StatusShareResponse rejected) {
}
