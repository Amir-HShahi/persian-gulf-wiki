package com.persiangulfwiki.core.subject.dto;

import com.persiangulfwiki.core.attribute.entity.AttributeValueType;
import com.persiangulfwiki.core.label.dto.LocalizedLabelResponse;

import io.swagger.v3.oas.annotations.media.Schema;

import org.jspecify.annotations.Nullable;

import java.util.List;

public record InfoboxRowResponse(
        String attribute,
        @Schema(nullable = true) @Nullable LocalizedLabelResponse label,
        @Schema(description = "Says which field of each value is set.\n\n" + AttributeValueType.VALUES_DOC)
        AttributeValueType valueType,
        @Schema(description = "Unit code of a NUMBER field (e.g. `km2`, `per_km2`), to be localized by the client.",
                nullable = true)
        @Nullable String unit,
        @Schema(description = "True when the value is computed from other data rather than recorded (population "
                + "density from population and area).")
        boolean isDerived,
        List<InfoboxValueResponse> values) {
}
