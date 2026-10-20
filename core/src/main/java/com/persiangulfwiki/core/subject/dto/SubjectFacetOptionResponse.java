package com.persiangulfwiki.core.subject.dto;

import com.persiangulfwiki.core.label.dto.LocalizedLabelResponse;

import io.swagger.v3.oas.annotations.media.Schema;

import org.jspecify.annotations.Nullable;

public record SubjectFacetOptionResponse(
        @Schema(description = "What to send back in the filter: a kind, a choice code, a subject id, or `true`.")
        String code,
        @Schema(nullable = true) @Nullable LocalizedLabelResponse label,
        @Schema(description = "How many subjects ticking this option would add to the result, given the filters of "
                + "every other group.")
        long count,
        @Schema(description = "A retired choice: still counted for subjects that hold it, but not offered for new "
                + "values.")
        boolean isDeprecated) {
}
