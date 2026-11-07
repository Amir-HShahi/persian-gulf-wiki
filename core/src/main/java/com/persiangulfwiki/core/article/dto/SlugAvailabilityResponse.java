package com.persiangulfwiki.core.article.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import org.jspecify.annotations.Nullable;

public record SlugAvailabilityResponse(
        @Schema(description = "The slug that was checked, as sent. Lets a caller checking on every keystroke drop "
                + "answers to an earlier value.")
        String slug,
        @Schema(description = "Whether no translation of any article uses this slug right now. A hint only: someone "
                + "else may take it before the article is submitted, which then fails with 409.")
        boolean isAvailable,
        @Schema(description = "Why the slug cannot be used, in the `Accept-Language` language, ready to show "
                + "under the field. Null when the slug is available.", nullable = true)
        @Nullable String reason) {
}
