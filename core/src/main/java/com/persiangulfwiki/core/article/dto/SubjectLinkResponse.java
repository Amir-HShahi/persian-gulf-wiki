package com.persiangulfwiki.core.article.dto;

import com.persiangulfwiki.core.label.dto.LocalizedLabelResponse;
import com.persiangulfwiki.core.subject.entity.SubjectKind;

import io.swagger.v3.oas.annotations.media.Schema;

import org.jspecify.annotations.Nullable;

public record SubjectLinkResponse(
        SubjectKind kind,
        @Schema(description = "The subject's name in the reader's language, else in Farsi with `isFallback` true.",
                nullable = true)
        @Nullable LocalizedLabelResponse name,
        @Schema(description = "Slug of the subject's main article in the reader's language, else in Farsi. Null when "
                + "the subject has no published article, so the link has no page to go to.", nullable = true)
        @Nullable String slug) {
}
