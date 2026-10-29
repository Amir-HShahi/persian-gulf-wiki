package com.persiangulfwiki.core.search.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import org.jspecify.annotations.Nullable;

import java.util.UUID;

// All plain text: a suggestion carries no snippet.
public record SearchSuggestionResponse(
        UUID translationId,
        UUID articleId,

        @Schema(description = "The translation's language.", example = "fa")
        String language,

        @Schema(description = "The translation's slug, for linking straight to the article.", example = "strait-of-hormuz")
        String slug,

        @Schema(description = "The article's title. Plain text -- escape it.", example = "تنگه هرمز")
        String title,

        @Schema(description = "The other name of the article's subject that matched what was typed, when the "
                + "title did not. Null when the title matched. Plain text -- escape it.", nullable = true)
        @Nullable String matchedName) {
}
