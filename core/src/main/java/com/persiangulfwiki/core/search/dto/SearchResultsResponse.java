package com.persiangulfwiki.core.search.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import org.jspecify.annotations.Nullable;

import java.util.List;

public record SearchResultsResponse(
        @Schema(description = "One page of matching published articles, best match first.")
        List<SearchHitResponse> items,

        @Schema(description = "Number of matching articles across all pages.", example = "42")
        long total,

        @Schema(description = "A corrected query to offer (\"Did you mean …?\") when this search found few or no "
                + "articles: each word no article contains is replaced by the closest one some article does. Null "
                + "when there were enough results or nothing to correct. Plain text -- escape it.", nullable = true,
                example = "Hormuz strait")
        @Nullable String didYouMean) {
}
