package com.persiangulfwiki.core.dev.dto;

import com.persiangulfwiki.core.label.dto.LabelReferentKind;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

// Labels only a referent this dev surface minted: a test group, definition or option, a test
// subject or a test topic. SUBJECT is accepted here although the admin endpoint refuses it, because it's the only
// way to name a subject before moderated subject revisions exist. KIND is refused: kind labels are
// shared, seeded rows.
public record DevTestLabelRequest(
        @NotNull LabelReferentKind referentKind,
        @NotNull UUID referentId,
        @NotNull @Pattern(regexp = "fa|en|ar") String language,
        @NotBlank(message = "{validation.label.required}")
        @Size(max = 200, message = "{validation.label.tooLong}") String value) {
}
