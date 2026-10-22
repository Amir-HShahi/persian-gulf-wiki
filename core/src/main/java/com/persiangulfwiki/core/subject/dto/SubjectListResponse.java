package com.persiangulfwiki.core.subject.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

public record SubjectListResponse(
        List<SubjectListItemResponse> items,
        @Schema(description = "Number of subjects matching every filter, across all pages.") long total,
        List<SubjectFacetResponse> facets) {
}
