package com.persiangulfwiki.core.subject.dto;

import com.persiangulfwiki.core.label.dto.LocalizedLabelResponse;

import io.swagger.v3.oas.annotations.media.Schema;

import org.jspecify.annotations.Nullable;

public record InfoboxOptionResponse(String code, @Schema(nullable = true) @Nullable LocalizedLabelResponse label) {
}
