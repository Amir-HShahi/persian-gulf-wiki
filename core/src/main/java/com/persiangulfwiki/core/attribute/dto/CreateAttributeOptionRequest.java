package com.persiangulfwiki.core.attribute.dto;

import org.jspecify.annotations.Nullable;

import com.persiangulfwiki.core.label.dto.LabelValuesRequest;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

// `code` is permanent (see V22) and upper case, e.g. CORAL_REEF, or a standard code such as an
// ISO 3166 country code. `sort` defaults to 0.
public record CreateAttributeOptionRequest(
        @NotBlank(message = "{validation.optionCode.required}")
        @Size(max = 60, message = "{validation.optionCode.tooLong}")
        @Pattern(regexp = "[A-Z0-9][A-Z0-9_]*", message = "{validation.optionCode.format}") String code,
        @Nullable Integer sort,
        @NotNull(message = "{validation.labels.required}") @Valid LabelValuesRequest labels) {
}
