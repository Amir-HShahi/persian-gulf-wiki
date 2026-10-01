package com.persiangulfwiki.core.attribute.dto;

import com.persiangulfwiki.core.attribute.entity.AttributeValueType;
import com.persiangulfwiki.core.attribute.validation.AttributeTypeSpec;
import com.persiangulfwiki.core.attribute.validation.ValidAttributeTypeSpec;
import com.persiangulfwiki.core.label.dto.LabelValuesRequest;
import com.persiangulfwiki.core.subject.entity.SubjectKind;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;

// Omitted booleans are false and an omitted `sort` is 0. They are boxed so that an omitted field
// binds as null instead of failing the whole body: Jackson 3 rejects a missing primitive. The same
// holds for every vocabulary request. `key` is permanent (see V22).
@ValidAttributeTypeSpec
public record CreateAttributeDefinitionRequest(
        @NotBlank(message = "{validation.attributeKey.required}")
        @Size(max = 60, message = "{validation.attributeKey.tooLong}")
        @Pattern(regexp = "[a-z][a-z0-9_]*", message = "{validation.attributeKey.format}") String key,
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
        @NotEmpty(message = "{validation.attributeKinds.required}") Set<@NotNull SubjectKind> kinds,
        @NotNull(message = "{validation.labels.required}") @Valid LabelValuesRequest labels)
        implements AttributeTypeSpec {
}
