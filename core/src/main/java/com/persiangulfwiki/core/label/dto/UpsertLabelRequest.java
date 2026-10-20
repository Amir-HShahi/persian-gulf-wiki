package com.persiangulfwiki.core.label.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpsertLabelRequest(
        @NotBlank(message = "{validation.label.required}")
        @Size(max = 200, message = "{validation.label.tooLong}") String value) {
}
