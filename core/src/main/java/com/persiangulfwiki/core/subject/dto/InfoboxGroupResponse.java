package com.persiangulfwiki.core.subject.dto;

import com.persiangulfwiki.core.label.dto.LocalizedLabelResponse;

import io.swagger.v3.oas.annotations.media.Schema;

import org.jspecify.annotations.Nullable;

import java.util.List;

public record InfoboxGroupResponse(
        String key,
        @Schema(nullable = true) @Nullable LocalizedLabelResponse label,
        List<InfoboxRowResponse> rows) {
}
