package com.persiangulfwiki.core.article.dto;

import com.persiangulfwiki.core.subject.dto.SubjectMainImageResponse;
import com.persiangulfwiki.core.subject.entity.SubjectKind;

import io.swagger.v3.oas.annotations.media.Schema;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

// Article-level fields, plus the approved-text summary of the article (language, slug, title,
// summary, revisionId, mainImage). The translations and revisions themselves -- including the drafts of an article
// with nothing approved yet -- are fetched separately via
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
        Instant updatedAt,
        @Schema(description = "Language of the translation that `slug`, `title`, `summary` and `revisionId` come "
                + "from: the reader's (from `Accept-Language`: fa, en or ar; anything else gets fa), else Farsi, else "
                + "the article's own language, else any other it is published in. Only approved text is shown: until "
                + "the article has an approved translation, these five fields are all null.", nullable = true)
        @Nullable String language,
        @Schema(description = "That translation's slug, for the article's page link.", nullable = true)
        @Nullable String slug,
        @Schema(nullable = true)
        @Nullable String title,
        @Schema(description = "May be absent even on a published article: a summary is optional.", nullable = true)
        @Nullable String summary,
        @Schema(description = "The translation's most recently approved revision, whose title and summary are shown.",
                nullable = true)
        @Nullable UUID revisionId,
        @Schema(description = "The main image of the article's subject. Absent when the article has no subject, or "
                + "its subject has no public main image.", nullable = true)
        @Nullable SubjectMainImageResponse mainImage) {
}
