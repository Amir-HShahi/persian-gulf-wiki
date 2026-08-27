package com.persiangulfwiki.core.expertreviewer.dto;

import jakarta.validation.constraints.NotBlank;

public record ApplicationRequest(
        @NotBlank(message = "{validation.entityType.required}") String entityType,
        @NotBlank(message = "{validation.justification.required}") String justification) {
}
