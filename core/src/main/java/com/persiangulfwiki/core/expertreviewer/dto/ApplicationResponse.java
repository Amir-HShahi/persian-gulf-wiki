package com.persiangulfwiki.core.expertreviewer.dto;

import java.time.Instant;
import java.util.UUID;

public record ApplicationResponse(
        UUID id,
        UUID applicantUserId,
        String entityType,
        String justification,
        String status,
        Instant createdAt,
        Instant reviewedAt) {
}
