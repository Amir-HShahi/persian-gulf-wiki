package com.persiangulfwiki.core.article.dto;

import com.persiangulfwiki.core.subject.dto.SubjectMainImageResponse;
import com.persiangulfwiki.core.subject.entity.SubjectKind;

import io.swagger.v3.oas.annotations.media.Schema;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

// One row of GET /api/articles?language=...: the article plus the requested language's
// translation, with title/summary read off that translation's approved (current) revision --
// never a draft, since this list is public and only moderation approval publishes content.
public record ArticleListItemResponse(
        UUID id,
        UUID subjectId,
        @Schema(description = "The kind of the subject this article is about; null when the article has no subject.\n\n"
                + SubjectKind.VALUES_DOC) SubjectKind subjectKind,
        String canonicalLanguage,
        UUID createdByUserId,
        Instant createdAt,
        Instant updatedAt,
        String language,
        String slug,
        String title,
        @Schema(description = "May be absent: a summary is optional on a revision.") String summary,
        @Schema(description = "The most recently approved revision of this translation -- the one whose "
                + "title and summary are shown here.") UUID revisionId,
        @Schema(description = "The main image of the article's subject. Absent when the article has no subject, or "
                + "its subject has no public main image.", nullable = true)
        @Nullable SubjectMainImageResponse mainImage) {
}
