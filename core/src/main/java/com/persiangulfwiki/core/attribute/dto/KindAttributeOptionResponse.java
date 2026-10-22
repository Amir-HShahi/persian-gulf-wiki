package com.persiangulfwiki.core.attribute.dto;

import com.persiangulfwiki.core.label.dto.LocalizedLabelResponse;

import org.jspecify.annotations.Nullable;

public record KindAttributeOptionResponse(
        String code,
        @Nullable LocalizedLabelResponse label,
        int sort,
        boolean isDeprecated) {
}
