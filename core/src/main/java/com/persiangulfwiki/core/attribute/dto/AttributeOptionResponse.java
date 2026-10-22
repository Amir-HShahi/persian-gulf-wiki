package com.persiangulfwiki.core.attribute.dto;

import com.persiangulfwiki.core.label.dto.LabelValuesResponse;

import java.util.UUID;

public record AttributeOptionResponse(
        UUID id,
        UUID attributeId,
        String code,
        int sort,
        boolean isDeprecated,
        LabelValuesResponse labels) {
}
