package com.persiangulfwiki.core.subject.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

public record SubjectReferencesResponse(
        List<SubjectListItemResponse> items,
        @Schema(description = "Number of referring subjects across all pages.") long total) {
}
