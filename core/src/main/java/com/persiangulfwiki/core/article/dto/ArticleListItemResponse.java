package com.persiangulfwiki.core.article.dto;

import com.persiangulfwiki.core.article.entity.EntityType;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

// One row of GET /api/articles?language=...: the article plus the requested language's
// translation, with title/summary read off that translation's approved (current) revision --
// never a draft, since this list is public and only moderation approval publishes content.
public record ArticleListItemResponse(
        UUID id,
        UUID subjectId,
        EntityType entityType,
        String canonicalLanguage,
        UUID createdByUserId,
        Instant createdAt,
        Instant updatedAt,
        String language,
        String slug,
        String title,
        @Schema(description = "May be absent: a summary is optional on a revision.") String summary,
        @Schema(description = "The most recently approved revision of this translation -- the one whose "
                + "title and summary are shown here.") UUID currentRevisionId) {
}
