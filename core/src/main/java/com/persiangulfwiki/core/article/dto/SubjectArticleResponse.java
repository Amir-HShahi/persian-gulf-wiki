package com.persiangulfwiki.core.article.dto;

import com.persiangulfwiki.core.subject.dto.SubjectMainImageResponse;

import io.swagger.v3.oas.annotations.media.Schema;

import org.jspecify.annotations.Nullable;

import java.util.UUID;

// One article of a subject, shown through a single approved translation picked for the reader.
public record SubjectArticleResponse(
        UUID articleId,
        @Schema(description = "Whether this is the subject's main article, whose slug is the subject's page.")
        boolean isMain,
        @Schema(description = "Language of the translation shown: the reader's, else Farsi, else the article's own "
                + "language, else any other it is published in.")
        String language,
        @Schema(description = "True when the translation shown is not in the reader's language.")
        boolean isFallback,
        String slug,
        String title,
        @Schema(description = "May be absent: a summary is optional on a revision.", nullable = true)
        @Nullable String summary,
        @Schema(description = "The translation's most recently approved revision, whose title and summary are shown.")
        UUID revisionId,
        @Schema(description = "The main image of the article's subject. Absent when the subject has no public main "
                + "image.", nullable = true)
        @Nullable SubjectMainImageResponse mainImage) {
}
