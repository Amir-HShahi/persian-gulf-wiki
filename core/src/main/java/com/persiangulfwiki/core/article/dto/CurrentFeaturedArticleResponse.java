package com.persiangulfwiki.core.article.dto;

import com.persiangulfwiki.core.subject.dto.SubjectMainImageResponse;
import com.persiangulfwiki.core.subject.entity.SubjectKind;

import io.swagger.v3.oas.annotations.media.Schema;

import org.jspecify.annotations.Nullable;

import java.util.UUID;

// The home page's featured-article card: title/summary from the translation's approved revision,
// image from the article's subject.
public record CurrentFeaturedArticleResponse(
        UUID articleId,
        @Schema(description = "The subject the article is about; absent when it has none.", nullable = true)
        @Nullable UUID subjectId,
        @Schema(description = "That subject's kind; absent when the article has no subject.\n\n" + SubjectKind.VALUES_DOC,
                nullable = true)
        @Nullable SubjectKind subjectKind,
        String language,
        @Schema(description = "The translation's slug, for the \"read more\" link.") String slug,
        String title,
        @Schema(description = "May be absent: a summary is optional.", nullable = true) @Nullable String summary,
        @Schema(description = "The translation's most recently approved revision -- the one whose title and summary "
                + "are shown here.") UUID revisionId,
        @Schema(description = "The main image of the article's subject. Absent when the article has no subject, "
                + "or its subject has no public main image.", nullable = true)
        @Nullable SubjectMainImageResponse mainImage,
        @Schema(description = "The scheduled window that picked this article. Absent when no window is live in this "
                + "language and the newest published article is shown instead.", nullable = true)
        @Nullable UUID featuredArticleId) {
}
