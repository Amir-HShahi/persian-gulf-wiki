package com.persiangulfwiki.core.subject.dto;

import com.persiangulfwiki.core.subject.entity.FactRank;

import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record SubjectFactValueResponse(
        @Nullable BigDecimal number,
        @Nullable String option,
        @Nullable UUID subjectId,
        @Nullable Boolean bool,
        @Nullable LocalDate date,
        @Nullable String text,
        @Nullable LocalizedTextResponse localized,
        @Nullable LocalDate asOf,
        @Nullable UUID sourceId,
        @Nullable FactRank rank) {
}
