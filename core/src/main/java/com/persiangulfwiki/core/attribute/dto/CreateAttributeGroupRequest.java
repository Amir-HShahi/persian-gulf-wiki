package com.persiangulfwiki.core.attribute.dto;

import org.jspecify.annotations.Nullable;

import com.persiangulfwiki.core.label.dto.LabelValuesRequest;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

// `sort` defaults to 0 when omitted. `key` is permanent: no endpoint renames it (see V22).
public record CreateAttributeGroupRequest(
        @NotBlank(message = "{validation.attributeKey.required}")
        @Size(max = 60, message = "{validation.attributeKey.tooLong}")
        @Pattern(regexp = "[a-z][a-z0-9_]*", message = "{validation.attributeKey.format}") String key,
        @Nullable Integer sort,
        @NotNull(message = "{validation.labels.required}") @Valid LabelValuesRequest labels) {
}
