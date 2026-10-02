package com.persiangulfwiki.core.subject.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import org.jspecify.annotations.Nullable;

import tools.jackson.databind.JsonNode;

public record SubjectFieldChangeResponse(
        @Schema(description = "Which part of the payload changed: `names.fa`, `aliases.en`, `facts.population`, "
                + "`geometry` or `mainImageMediaId`.")
        String field,
        @Schema(description = "The value before, in the payload's own shape -- a string, a list of strings or a list "
                + "of fact values. Null when the field was not set.", nullable = true)
        @Nullable JsonNode before,
        @Schema(description = "The value this revision sets, in the same shape. Null when it removes the field.",
                nullable = true)
        @Nullable JsonNode after) {
}
