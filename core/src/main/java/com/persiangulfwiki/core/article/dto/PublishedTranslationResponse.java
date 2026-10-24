package com.persiangulfwiki.core.article.dto;

import com.persiangulfwiki.core.article.entity.TranslationState;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

public record PublishedTranslationResponse(
        UUID id,
        String language,
        String slug,
        TranslationState translationState,
        Instant updatedAt,
        @Schema(description = "Whether this is the translation the article's other translations are translated from.")
        boolean isSource) {
}
