package com.persiangulfwiki.core.common.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

public record PageResponse<T>(
        List<T> items,
        @Schema(description = "Number of matching items across all pages.") long total) {
}
