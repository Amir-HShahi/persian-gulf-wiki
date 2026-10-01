package com.persiangulfwiki.core.dev.dto;

import com.persiangulfwiki.core.attribute.entity.AttributeValueType;
import com.persiangulfwiki.core.attribute.validation.AttributeTypeSpec;
import com.persiangulfwiki.core.attribute.validation.ValidAttributeTypeSpec;
import com.persiangulfwiki.core.subject.entity.SubjectKind;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;

// Only `valueType` is required, so a suite can mint one definition per type. `key` defaults to a
// generated e2e_... key, `groupId` to the first group by sort, `kinds` to [ISLAND]. The same
// type-dependent rules as the admin endpoint apply (they are database constraints), but
// `isDeprecated` can be set at creation and `faLabel` can be left out to mint a definition with
// no label at all — neither of which the admin endpoint allows.
@ValidAttributeTypeSpec
public record DevTestAttributeDefinitionRequest(
        @Size(max = 60, message = "{validation.attributeKey.tooLong}")
        @Pattern(regexp = "[a-z][a-z0-9_]*", message = "{validation.attributeKey.format}") @Nullable String key,
        @NotNull(message = "{validation.valueType.required}") AttributeValueType valueType,
        @Nullable UUID groupId,
        @Size(max = 20, message = "{validation.attributeUnit.tooLong}")
        @Pattern(regexp = "[a-z][a-z0-9_]*", message = "{validation.attributeUnit.format}") @Nullable String unit,
        @Nullable Integer sort,
        @Nullable Boolean isMulti,
        @Nullable Boolean isRequired,
        @Nullable Boolean isInfobox,
        @Nullable Boolean isFilterable,
        @Nullable SubjectKind refKind,
        @Nullable BigDecimal minValue,
        @Nullable BigDecimal maxValue,
        @Nullable Boolean isDeprecated,
        @Nullable Set<SubjectKind> kinds,
        @Size(max = 200, message = "{validation.label.tooLong}") @Nullable String faLabel)
        implements AttributeTypeSpec {
}
