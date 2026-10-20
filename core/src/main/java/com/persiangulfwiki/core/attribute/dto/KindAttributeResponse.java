package com.persiangulfwiki.core.attribute.dto;

import com.persiangulfwiki.core.attribute.entity.AttributeValueType;
import com.persiangulfwiki.core.label.dto.LocalizedLabelResponse;
import com.persiangulfwiki.core.subject.entity.SubjectKind;

import io.swagger.v3.oas.annotations.media.Schema;

import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.util.List;

// Deprecated fields and options are included, flagged: the edit form still has to render a
// value a subject already holds, even though it must not offer it for new ones.
public record KindAttributeResponse(
        String key,
        @Nullable LocalizedLabelResponse label,
        AttributeValueType valueType,
        @Nullable String unit,
        int sort,
        boolean isMulti,
        boolean isRequired,
        boolean isInfobox,
        boolean isFilterable,
        @Nullable SubjectKind refKind,
        @Nullable BigDecimal minValue,
        @Nullable BigDecimal maxValue,
        boolean isDeprecated,
        @Schema(description = "Computed from other data rather than recorded (population density, has a 360° tour): "
                + "show it, filter by it, but never offer it in the edit form.")
        boolean isDerived,
        @Schema(description = "Its code is shown on listing cards (under `badges`).")
        boolean isBadge,
        List<KindAttributeOptionResponse> options) {
}
