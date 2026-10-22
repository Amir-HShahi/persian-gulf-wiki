package com.persiangulfwiki.core.subject.dto;

import com.persiangulfwiki.core.label.dto.LocalizedLabelResponse;
import com.persiangulfwiki.core.subject.entity.SubjectKind;

import io.swagger.v3.oas.annotations.media.Schema;

public record SubjectKindCountResponse(
        SubjectKind kind,
        @Schema(description = "The kind's plural name in the reader's language, or the Farsi one marked as a "
                + "fallback. Null only if the kind has no Farsi name either.")
        LocalizedLabelResponse name,
        @Schema(description = "How many subjects of this kind exist. Deleted subjects and subjects merged into "
                + "another are not counted.")
        long count) {
}
