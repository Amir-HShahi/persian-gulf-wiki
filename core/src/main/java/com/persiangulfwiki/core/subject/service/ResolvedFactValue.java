package com.persiangulfwiki.core.subject.service;

import com.persiangulfwiki.core.subject.dto.LocalizedTextRequest;
import com.persiangulfwiki.core.subject.entity.FactRank;

import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

// One validated fact value with its codes resolved to ids, ready to become a subject_facts row.
// Exactly one value field is set.
record ResolvedFactValue(
        UUID attributeId,
        int sort,
        @Nullable BigDecimal number,
        @Nullable UUID optionId,
        @Nullable UUID refSubjectId,
        @Nullable Boolean bool,
        @Nullable LocalDate date,
        @Nullable String text,
        @Nullable LocalizedTextRequest localized,
        @Nullable LocalDate asOf,
        FactRank rank,
        @Nullable UUID sourceId) {
}
