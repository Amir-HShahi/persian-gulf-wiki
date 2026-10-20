package com.persiangulfwiki.core.subject.dto;

import com.persiangulfwiki.core.label.dto.LocalizedLabelResponse;

import io.swagger.v3.oas.annotations.media.Schema;

import org.jspecify.annotations.Nullable;

import java.util.List;

public record SubjectFacetResponse(
        @Schema(description = "`kind`, or the key of a filterable field (the query parameter is `attr.{key}`).")
        String key,
        @Schema(description = "Null for `kind`: the page supplies its own heading for it.", nullable = true)
        @Nullable LocalizedLabelResponse label,
        List<SubjectFacetOptionResponse> options) {
}
