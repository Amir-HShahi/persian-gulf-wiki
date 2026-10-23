package com.persiangulfwiki.core.expertreviewer.dto;

import com.persiangulfwiki.core.expertreviewer.entity.ApplicationStatus;
import com.persiangulfwiki.core.subject.entity.SubjectKind;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

public record ApplicationResponse(
        UUID id,
        UUID applicantUserId,
        SubjectKind expertise,
        String justification,
        @Schema(description = ApplicationStatus.VALUES_DOC) String status,
        Instant createdAt,
        Instant reviewedAt) {
}
