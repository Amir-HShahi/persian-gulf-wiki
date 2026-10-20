package com.persiangulfwiki.core.moderation.dto;

import com.persiangulfwiki.core.moderation.entity.ModerationTaskState;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

// decisions is every round this task has been through, oldest first -- empty for a task
// nobody has decided yet, and more than one only for a task that went through
// REQUEST_CHANGES. Carried inline rather than behind a separate endpoint because a moderator
// picking up a returned task needs to read what the previous round asked for before deciding
// it again.
//
// Exactly one of revisionId, mediaMetadataVersionId and subjectRevisionId is non-null: which one
// says what kind of thing the task judges, and so which detail route a client follows to show it.
public record ModerationTaskResponse(
        UUID id,
        @Schema(description = "The article revision this task judges. Null when the task judges something else.",
                nullable = true)
        UUID revisionId,
        @Schema(description = "The gallery item metadata version this task judges. Null when the task judges "
                + "something else. Media tasks accept only APPROVE and REJECT.", nullable = true)
        UUID mediaMetadataVersionId,
        @Schema(description = "The subject revision (a proposed new subject, or an edit of one) this task judges. "
                + "Null when the task judges something else.", nullable = true)
        UUID subjectRevisionId,
        ModerationTaskState state,
        UUID claimedBy,
        Instant claimedAt,
        Instant createdAt,
        Instant updatedAt,
        List<ModerationDecisionResponse> decisions) {
}
