package com.persiangulfwiki.core.article.dto;

import com.persiangulfwiki.core.article.entity.RevisionStatus;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
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
        Map<UUID, SubjectLinkResponse> subjectLinks) {
}
