package com.persiangulfwiki.core.subject.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import org.jspecify.annotations.Nullable;

import java.util.List;

public record SubjectFactRequest(
        @Schema(description = "The field's key, as listed by GET /api/subject-kinds/{kind}/attributes.")
        @Nullable String attribute,
        @Nullable List<SubjectFactValueRequest> values) {
}
