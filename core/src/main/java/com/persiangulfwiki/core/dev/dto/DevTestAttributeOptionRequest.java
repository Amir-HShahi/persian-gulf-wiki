package com.persiangulfwiki.core.dev.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.jspecify.annotations.Nullable;

// Every field optional. `code` defaults to a generated E2E_... code. `isDeprecated` can be set at
// creation, which the admin endpoint does not allow, and `faLabel` can be left out.
public record DevTestAttributeOptionRequest(
        @Size(max = 60, message = "{validation.optionCode.tooLong}")
        @Pattern(regexp = "[A-Z0-9][A-Z0-9_]*", message = "{validation.optionCode.format}") @Nullable String code,
        @Nullable Integer sort,
        @Nullable Boolean isDeprecated,
        @Size(max = 200, message = "{validation.label.tooLong}") @Nullable String faLabel) {
}
