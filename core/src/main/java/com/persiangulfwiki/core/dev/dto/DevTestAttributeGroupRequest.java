package com.persiangulfwiki.core.dev.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.jspecify.annotations.Nullable;

// Every field optional. `key` defaults to a generated e2e_... key. Without `faLabel` the group
// has no label at all — a state the admin endpoint cannot produce, since it requires Farsi.
public record DevTestAttributeGroupRequest(
        @Size(max = 60, message = "{validation.attributeKey.tooLong}")
        @Pattern(regexp = "[a-z][a-z0-9_]*", message = "{validation.attributeKey.format}") @Nullable String key,
        @Nullable Integer sort,
        @Size(max = 200, message = "{validation.label.tooLong}") @Nullable String faLabel) {
}
