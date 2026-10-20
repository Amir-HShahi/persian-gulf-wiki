package com.persiangulfwiki.core.subject.dto;

import com.persiangulfwiki.core.label.dto.LocalizedLabelResponse;

import io.swagger.v3.oas.annotations.media.Schema;

import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

// Exactly one of number/option/subject/bool/date/text/localizedText is set, the one the row's
// valueType names.
public record InfoboxValueResponse(
        @Schema(description = "NUMBER: the raw value; format its digits for the reader's locale.", nullable = true)
        @Nullable BigDecimal number,
        @Schema(description = "ENUM: the code and its label.", nullable = true)
        @Nullable InfoboxOptionResponse option,
        @Schema(description = "SUBJECT_REF: the subject referred to.", nullable = true)
        @Nullable InfoboxSubjectResponse subject,
        @Schema(description = "BOOL.", nullable = true)
        @Nullable Boolean bool,
        @Schema(description = "DATE.", nullable = true)
        @Nullable LocalDate date,
        @Schema(description = "PLAIN_TEXT: language-neutral text such as a scientific name.", nullable = true)
        @Nullable String text,
        @Schema(description = "LOCALIZED_TEXT: the text in the reader's language, or the Farsi text marked as a "
                + "fallback.", nullable = true)
        @Nullable LocalizedLabelResponse localizedText,
        @Schema(description = "When the value held, e.g. a census date.", nullable = true)
        @Nullable LocalDate asOf,
        @Schema(description = "The source cited for the value. Always null on a derived value.", nullable = true)
        @Nullable UUID sourceId) {
}
