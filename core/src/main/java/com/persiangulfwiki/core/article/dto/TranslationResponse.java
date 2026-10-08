package com.persiangulfwiki.core.article.dto;

import com.persiangulfwiki.core.article.entity.TranslationState;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
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
        Instant updatedAt,
        @Schema(description = "Ids of the topics the article is about (see `GET /api/topics`): those of the approved "
                + "text in the article's original language, the same for every translation. Empty until that text "
                + "has been approved.")
        List<UUID> topicIds) {
}
