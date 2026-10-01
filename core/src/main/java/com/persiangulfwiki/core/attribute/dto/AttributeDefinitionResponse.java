package com.persiangulfwiki.core.attribute.dto;

import com.persiangulfwiki.core.attribute.entity.AttributeValueType;
import com.persiangulfwiki.core.label.dto.LabelValuesResponse;
import com.persiangulfwiki.core.subject.entity.SubjectKind;

import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record AttributeDefinitionResponse(
        UUID id,
        String key,
        AttributeValueType valueType,
        @Nullable String unit,
        UUID groupId,
        int sort,
        boolean isMulti,
        boolean isRequired,
        boolean isInfobox,
        boolean isFilterable,
        @Nullable SubjectKind refKind,
        @Nullable BigDecimal minValue,
        @Nullable BigDecimal maxValue,
        boolean isDeprecated,
        List<SubjectKind> kinds,
        LabelValuesResponse labels) {
}
