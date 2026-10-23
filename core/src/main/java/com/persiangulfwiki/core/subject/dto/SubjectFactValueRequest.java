package com.persiangulfwiki.core.subject.dto;

import com.persiangulfwiki.core.subject.entity.FactRank;

import io.swagger.v3.oas.annotations.media.Schema;

import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

// Exactly one of the seven value fields is set, the one matching the field's value type.
public record SubjectFactValueRequest(
        @Schema(description = "For a NUMBER field, in the field's unit. Requires `sourceId`.", nullable = true)
        @Nullable BigDecimal number,
        @Schema(description = "For an ENUM field: one of its option codes.", nullable = true)
        @Nullable String option,
        @Schema(description = "For a SUBJECT_REF field: the id of a subject of the field's referenced kind.",
                nullable = true)
        @Nullable UUID subjectId,
        @Schema(description = "For a BOOL field.", nullable = true)
        @Nullable Boolean bool,
        @Schema(description = "For a DATE field, as YYYY-MM-DD.", nullable = true)
        @Nullable LocalDate date,
        @Schema(description = "For a PLAIN_TEXT field: language-neutral text such as a scientific name.",
                nullable = true)
        @Nullable String text,
        @Schema(description = "For a LOCALIZED_TEXT field: one text per language, Farsi required.", nullable = true)
        @Nullable LocalizedTextRequest localized,
        @Schema(description = "When the value held, e.g. a census date, as YYYY-MM-DD.", nullable = true)
        @Nullable LocalDate asOf,
        @Schema(description = "The source the value is cited from. Required on a NUMBER value.", nullable = true)
        @Nullable UUID sourceId,
        @Schema(description = "Defaults to PREFERRED. A field that takes a single value may still have several "
                + "values, but at most one PREFERRED.\n\n" + FactRank.VALUES_DOC, nullable = true)
        @Nullable FactRank rank) {
}
