package com.persiangulfwiki.core.article.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

// One scheduled window, as admins manage it -- not what the home page shows (that is
// CurrentFeaturedArticleResponse).
public record FeaturedArticleResponse(
        UUID id,
        UUID articleId,
        Instant startsAt,
        @Schema(description = "Absent for a window with no end.", nullable = true) @Nullable Instant endsAt,
        @Schema(description = "The admin who scheduled it; absent once that account is deleted.", nullable = true)
        @Nullable UUID featuredByUserId,
        Instant createdAt) {
}
