package com.persiangulfwiki.core.subject.dto;

import com.persiangulfwiki.core.label.dto.LocalizedLabelResponse;
import com.persiangulfwiki.core.subject.entity.SubjectKind;

import io.swagger.v3.oas.annotations.media.Schema;

import org.jspecify.annotations.Nullable;

import java.util.UUID;

public record SubjectLookupItemResponse(
        UUID id,
        SubjectKind kind,
        @Schema(description = "The subject's name in the reader's language, else in Farsi.", nullable = true)
        @Nullable LocalizedLabelResponse name,
        @Schema(description = "Slug of the subject's main article in the reader's language, else in Farsi. Null when "
                + "the subject has no published article.", nullable = true)
        @Nullable String slug,
        @Schema(description = "What the search term was found in.\n\n" + SubjectLookupMatch.VALUES_DOC)
        SubjectLookupMatch matchedOn,
        @Schema(description = "The name, alias or scientific name that matched, as stored -- which may differ from "
                + "`name`, e.g. an alias or a name in another language.")
        String matchedValue,
        @Schema(description = "Language of `matchedValue` (fa, en or ar); null for a scientific name.", nullable = true)
        @Nullable String matchedLanguage) {
}
