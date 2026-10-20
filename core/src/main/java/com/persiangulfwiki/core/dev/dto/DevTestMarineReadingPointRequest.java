package com.persiangulfwiki.core.dev.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;

// @Digits mirrors the column's NUMERIC(12, 4), so an out-of-range value is a 400 here rather
// than a numeric-overflow 500 from Postgres.
public record DevTestMarineReadingPointRequest(
        @NotNull Instant observedAt,
        @NotNull @Digits(integer = 8, fraction = 4) BigDecimal value) {
}
