package com.persiangulfwiki.core.dev.dto;

import jakarta.validation.constraints.NotNull;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

public record DevTestFeaturedArticleRequest(
        @NotNull UUID articleId,
        @Nullable Instant startsAt,
        @Nullable Instant endsAt) {
}
