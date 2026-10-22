package com.persiangulfwiki.core.article.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotNull;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

public record CreateFeaturedArticleRequest(
        @NotNull(message = "{validation.featuredArticle.articleId.required}") UUID articleId,
        @Schema(description = "When the article starts being featured. Defaults to now.", nullable = true)
        @Nullable Instant startsAt,
        @Schema(description = "When it stops being featured; must be after `startsAt`. Absent means it stays "
                + "featured until a later-starting one begins or this one is removed.", nullable = true)
        @Nullable Instant endsAt) {
}
