package com.persiangulfwiki.core.article.dto;

import com.persiangulfwiki.core.subject.entity.SubjectKind;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

// Article-level fields only -- the canonical translation and its first revision, created
// alongside the article by the same request, are fetched separately via
// GET /api/articles/{articleId}/translations/{language}, matching the
// article/translation/revision split the rest of this API follows.
public record ArticleResponse(
        UUID id,
        UUID subjectId,
        @Schema(description = "The kind of the subject this article is about; null when the article has no subject.\n\n"
                + SubjectKind.VALUES_DOC) SubjectKind subjectKind,
        String canonicalLanguage,
        UUID createdByUserId,
        Instant createdAt,
        Instant updatedAt) {
}
