package com.persiangulfwiki.core.attribute.dto;

import com.persiangulfwiki.core.attribute.entity.AttributeValueType;
import com.persiangulfwiki.core.attribute.validation.AttributeTypeSpec;
import com.persiangulfwiki.core.attribute.validation.ValidAttributeTypeSpec;
import com.persiangulfwiki.core.subject.entity.SubjectKind;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;

// Replaces every mutable field in place, not a merge-patch — same convention as
// UpdateRevisionRequest. A merge-patch couldn't tell "clear the unit" from "leave it", and
// changing valueType away from NUMBER has to clear it. Omitted nullable fields are cleared,
// omitted booleans are false. `key` and the labels are not here: the key is permanent and labels
// change through PUT /api/admin/labels.
@ValidAttributeTypeSpec
public record UpdateAttributeDefinitionRequest(
        @NotNull(message = "{validation.valueType.required}") AttributeValueType valueType,
        @Size(max = 20, message = "{validation.attributeUnit.tooLong}")
        @Pattern(regexp = "[a-z][a-z0-9_]*", message = "{validation.attributeUnit.format}") @Nullable String unit,
        @NotNull(message = "{validation.attributeGroupId.required}") UUID groupId,
        @Nullable Integer sort,
        @Nullable Boolean isMulti,
        @Nullable Boolean isRequired,
        @Nullable Boolean isInfobox,
        @Nullable Boolean isFilterable,
        @Nullable SubjectKind refKind,
        @Nullable BigDecimal minValue,
        @Nullable BigDecimal maxValue,
        @Nullable Boolean isDeprecated,
        @NotEmpty(message = "{validation.attributeKinds.required}") Set<@NotNull SubjectKind> kinds)
        implements AttributeTypeSpec {
}
