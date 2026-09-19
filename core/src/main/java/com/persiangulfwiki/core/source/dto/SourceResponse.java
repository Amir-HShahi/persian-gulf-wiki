package com.persiangulfwiki.core.source.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record SourceResponse(
        UUID id,
        String title,
        String url,
        String publisher,
        LocalDate publishedOn,
        UUID createdByUserId,
        Instant createdAt) {
}
