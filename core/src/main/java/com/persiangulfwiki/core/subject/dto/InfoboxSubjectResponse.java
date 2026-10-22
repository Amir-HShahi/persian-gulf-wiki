package com.persiangulfwiki.core.subject.dto;

import com.persiangulfwiki.core.label.dto.LocalizedLabelResponse;

import io.swagger.v3.oas.annotations.media.Schema;

import org.jspecify.annotations.Nullable;

import java.util.UUID;

public record InfoboxSubjectResponse(
        UUID id,
        @Schema(nullable = true) @Nullable LocalizedLabelResponse name,
        @Schema(description = "Slug of the subject's main article in the reader's language, else in Farsi. Null when "
                + "the subject has no published article, so it has no page to link to.", nullable = true)
        @Nullable String slug) {
}
