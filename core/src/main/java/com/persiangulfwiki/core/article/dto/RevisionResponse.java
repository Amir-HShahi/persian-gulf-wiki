package com.persiangulfwiki.core.article.dto;

import com.persiangulfwiki.core.article.entity.RevisionStatus;

import io.swagger.v3.oas.annotations.media.Schema;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

public record RevisionResponse(
        UUID id,
        UUID translationId,
        int revisionNumber,
        UUID parentRevisionId,
        String title,
        @Schema(description = "The structured content. A node "
                + "`{\"type\": \"subjectLink\", \"subjectId\": \"…\"}` links to a subject; render it with that "
                + "subject's entry in `subjectLinks`.")
        JsonNode body,
        String summary,
        RevisionStatus status,
        UUID authorId,
        Instant createdAt,
        @Schema(description = "Subject id to what a subject link in `body` shows: the subject's name in the reader's "
                + "language (from `Accept-Language`: fa, en or ar; anything else gets fa) and its page's slug. A "
                + "link to a subject that was merged into another shows that other subject. A linked subject that no "
                + "longer exists or has been deleted is absent; show the link as plain text.")
        Map<UUID, SubjectLinkResponse> subjectLinks,
        @Schema(description = "Ids of the topics this revision says the article is about (see `GET /api/topics`). "
                + "Always empty on a revision that is not in the article's original language: translations inherit "
                + "the article's topics, listed on the translation.")
        List<UUID> topicIds,
        @Schema(description = "Whether a translation model wrote this text rather than a person. Show it with an AI "
                + "label.")
        boolean isMachineGenerated,
        @Schema(description = "The model that wrote the text, e.g. `qwen3.5:4b`. Null whenever `isMachineGenerated` is "
                + "false.", nullable = true)
        @Nullable String machineModel) {
}
