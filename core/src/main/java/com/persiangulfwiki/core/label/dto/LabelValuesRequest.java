package com.persiangulfwiki.core.label.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.jspecify.annotations.Nullable;

// The labels a new group, field or option is created with. Farsi is canonical and required, so
// nothing is ever created without a name; English and Arabic are optional, but a supplied one
// must not be blank — "" would be stored as a label and shown instead of the Farsi fallback.
public record LabelValuesRequest(
        @NotBlank(message = "{validation.labelFa.required}")
        @Size(max = 200, message = "{validation.label.tooLong}") String fa,
        @Pattern(regexp = "(?s).*\\S.*", message = "{validation.label.blank}")
        @Size(max = 200, message = "{validation.label.tooLong}") @Nullable String en,
        @Pattern(regexp = "(?s).*\\S.*", message = "{validation.label.blank}")
        @Size(max = 200, message = "{validation.label.tooLong}") @Nullable String ar) {
}
