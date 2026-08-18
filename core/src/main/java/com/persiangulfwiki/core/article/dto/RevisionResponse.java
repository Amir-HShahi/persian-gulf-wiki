package com.persiangulfwiki.core.article.dto;

import com.persiangulfwiki.core.article.entity.RevisionStatus;

import java.time.Instant;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

public record RevisionResponse(
        UUID id,
        UUID translationId,
        int revisionNumber,
        UUID parentRevisionId,
        String title,
        JsonNode body,
        String summary,
        RevisionStatus status,
        UUID authorId,
        Instant createdAt) {
}
