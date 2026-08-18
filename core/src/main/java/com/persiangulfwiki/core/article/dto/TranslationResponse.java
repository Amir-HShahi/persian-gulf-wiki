package com.persiangulfwiki.core.article.dto;

import com.persiangulfwiki.core.article.entity.TranslationState;

import java.time.Instant;
import java.util.UUID;

public record TranslationResponse(
        UUID id,
        UUID articleId,
        String language,
        String slug,
        UUID currentRevisionId,
        UUID sourceRevisionId,
        TranslationState translationState,
        Instant createdAt,
        Instant updatedAt) {
}
