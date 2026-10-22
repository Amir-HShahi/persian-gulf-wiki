package com.persiangulfwiki.core.subject.dto;

import java.time.Instant;
import java.util.UUID;

public record ExpertVerificationResponse(
        UUID id,
        UUID subjectRevisionId,
        UUID subjectId,
        int revisionNumber,
        UUID expertId,
        Instant verifiedAt) {
}
