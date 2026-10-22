package com.persiangulfwiki.core.subject.dto;

import com.persiangulfwiki.core.subject.entity.SubjectKind;

import io.swagger.v3.oas.annotations.media.Schema;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.UUID;

public record VerificationQueueItemResponse(
        UUID subjectId,
        SubjectKind kind,
        @Schema(description = "The subject's names as the current revision gives them, in every language.",
                nullable = true)
        @Nullable LocalizedTextResponse names,
        @Schema(description = "The revision readers see now -- the one to verify.")
        UUID revisionId,
        int revisionNumber,
        @Schema(description = "When that revision was approved.")
        Instant approvedAt,
        @Schema(description = "Never VERIFIED here: the queue lists only subjects whose current revision has no "
                + "expert confirmation yet.")
        SubjectVerificationResponse verification) {
}
