package com.persiangulfwiki.core.subject.dto;

import com.persiangulfwiki.core.label.dto.LocalizedLabelResponse;
import com.persiangulfwiki.core.subject.entity.SubjectKind;

import io.swagger.v3.oas.annotations.media.Schema;

import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.UUID;

public record SubjectListItemResponse(
        UUID id,
        SubjectKind kind,
        @Schema(nullable = true) @Nullable LocalizedLabelResponse name,
        @Schema(description = "Slug of the subject's main article in the reader's language, else in Farsi. Null when "
                + "the subject has no published article: the card then has no page to link to.", nullable = true)
        @Nullable String slug,
        @Schema(description = "The card image. Null when the subject has no public image: show a stand-in.", nullable = true)
        @Nullable SubjectMainImageResponse mainImage,
        @Schema(description = "Field key to choice code, for the fields shown on listing cards (e.g. "
                + "`{\"iucn_status\": \"EN\"}`). Labels for the codes are in `facets`.")
        Map<String, String> badges) {
}
