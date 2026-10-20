package com.persiangulfwiki.core.subject.dto;

import com.persiangulfwiki.core.subject.entity.SubjectKind;
import com.persiangulfwiki.core.subject.entity.SubjectRevisionStatus;

import io.swagger.v3.oas.annotations.media.Schema;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

public record SubjectRevisionResponse(
        UUID id,
        @Schema(description = "Null on a proposal that has not been approved yet; approval creates the subject.",
                nullable = true)
        @Nullable UUID subjectId,
        SubjectKind kind,
        @Schema(description = "1-based per subject. A proposal is always 1.")
        int revisionNumber,
        @Schema(description = "The live revision this edit was copied from. Null on a proposal, and on an edit of a "
                + "subject that had no approved revision yet.", nullable = true)
        @Nullable UUID baseRevisionId,
        SubjectPayloadResponse payload,
        @Nullable String summary,
        SubjectRevisionStatus status,
        UUID authorId,
        Instant createdAt) {
}
