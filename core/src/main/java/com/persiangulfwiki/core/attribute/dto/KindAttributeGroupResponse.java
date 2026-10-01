package com.persiangulfwiki.core.attribute.dto;

import com.persiangulfwiki.core.label.dto.LocalizedLabelResponse;

import org.jspecify.annotations.Nullable;

import java.util.List;

public record KindAttributeGroupResponse(
        String key,
        @Nullable LocalizedLabelResponse label,
        int sort,
        List<KindAttributeResponse> attributes) {
}
