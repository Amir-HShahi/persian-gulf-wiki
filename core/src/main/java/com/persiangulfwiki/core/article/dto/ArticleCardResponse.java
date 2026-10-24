package com.persiangulfwiki.core.article.dto;

import com.persiangulfwiki.core.subject.dto.SubjectMainImageResponse;

import io.swagger.v3.oas.annotations.media.Schema;

import org.jspecify.annotations.Nullable;

import java.util.UUID;

// Internal only: no response exposes it as an object. ArticleResponse and SubjectArticleResponse copy
// its fields flat. Read off one approved translation only -- never a draft.
@Schema(description = "Enough of an article to show it as a link or card. The text comes from one approved "
        + "translation, picked for the reader: their language (from `Accept-Language`: fa, en or ar; anything else "
        + "gets fa), else Farsi, else the article's own language, else any other it is published in. Until the "
        + "article has an approved translation, `language`, `slug`, `title`, `summary` and `revisionId` are all null.")
public record ArticleCardResponse(
        UUID id,
        @Schema(description = "Language of the translation shown.", nullable = true)
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
