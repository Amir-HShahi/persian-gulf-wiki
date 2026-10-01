package com.persiangulfwiki.core.attribute.dto;

import com.persiangulfwiki.core.label.dto.LabelValuesResponse;

import java.util.UUID;

public record AttributeGroupResponse(UUID id, String key, int sort, LabelValuesResponse labels) {
}
