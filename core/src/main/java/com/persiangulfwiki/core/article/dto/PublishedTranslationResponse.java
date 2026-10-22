package com.persiangulfwiki.core.article.dto;

import com.persiangulfwiki.core.article.entity.TranslationState;

import java.time.Instant;
import java.util.UUID;

public record PublishedTranslationResponse(
        UUID id,
        String language,
        String slug,
        TranslationState translationState,
        Instant updatedAt) {
}
