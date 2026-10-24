package com.persiangulfwiki.core.article.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

public record PublishedRevisionResponse(
        UUID id,
        int revisionNumber,
        String title,
        @Schema(description = "May be absent: a summary is optional on a revision.", nullable = true)
        @Nullable String summary,
        @Schema(description = "The structured content. A node "
                + "`{\"type\": \"subjectLink\", \"subjectId\": \"…\"}` links to a subject; render it with that "
                + "subject's entry in `subjectLinks`.")
        JsonNode body,
        @Schema(description = "When this revision was written.")
        Instant createdAt,
        @Schema(description = "Whether a translation model wrote this text rather than a person. Show it with an AI "
                + "label.")
        boolean isMachineGenerated,
        @Schema(description = "The model that wrote the text, e.g. `qwen3.5:4b`. Null whenever `isMachineGenerated` is "
                + "false.", nullable = true)
        @Nullable String machineModel) {
}
