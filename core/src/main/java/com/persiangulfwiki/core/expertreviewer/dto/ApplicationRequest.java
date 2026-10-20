package com.persiangulfwiki.core.expertreviewer.dto;

import com.persiangulfwiki.core.subject.entity.SubjectKind;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ApplicationRequest(
        @NotNull(message = "{validation.expertise.required}") SubjectKind expertise,
        @NotBlank(message = "{validation.justification.required}") String justification) {
}
