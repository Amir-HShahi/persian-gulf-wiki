package com.persiangulfwiki.core.dev.dto;

import java.util.UUID;

public record DevTestExpertVerificationResponse(
        UUID verificationId,
        UUID subjectRevisionId,
        UUID subjectId,
        int revisionNumber,
        UUID expertId,
        boolean isOnLiveRevision) {
}
