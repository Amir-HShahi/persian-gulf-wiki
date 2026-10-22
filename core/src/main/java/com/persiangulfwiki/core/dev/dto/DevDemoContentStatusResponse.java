package com.persiangulfwiki.core.dev.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import org.jspecify.annotations.Nullable;

import java.time.Instant;

public record DevDemoContentStatusResponse(
        DevDemoContentRunStatus status,
        @Schema(description = "What the fill is doing now, e.g. \"Writing articles\". Absent unless RUNNING.",
                nullable = true)
        @Nullable String step,
        @Schema(description = "Items of the current step done so far, out of `total`. Both are 0 for a step that is "
                + "not counted.")
        int done,
        int total,
        @Schema(description = "When the latest fill started. Absent while IDLE.", nullable = true)
        @Nullable Instant startedAt,
        @Schema(description = "When the latest fill finished, successfully or not. Absent while RUNNING or IDLE.",
                nullable = true)
        @Nullable Instant finishedAt,
        @Schema(description = "What the fill created. Present only when SUCCEEDED.", nullable = true)
        @Nullable DevDemoContentResponse result,
        @Schema(description = "Why the fill stopped. Present only when FAILED. Whatever it had created by then is "
                + "removed by the next fill or removal.", nullable = true)
        @Nullable String error) {
}
