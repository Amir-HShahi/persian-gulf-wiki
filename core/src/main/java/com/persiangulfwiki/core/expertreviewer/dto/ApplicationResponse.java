package com.persiangulfwiki.core.expertreviewer.dto;

import com.persiangulfwiki.core.subject.entity.SubjectKind;

import java.time.Instant;
import java.util.UUID;

public record ApplicationResponse(
        UUID id,
        UUID applicantUserId,
        SubjectKind expertise,
        String justification,
        String status,
        Instant createdAt,
        Instant reviewedAt) {
}
