package com.persiangulfwiki.core.moderation.dto;

import com.persiangulfwiki.core.moderation.entity.ModerationTaskState;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

// decisions is every round this task has been through, oldest first -- empty for a task
// nobody has decided yet, and more than one only for a task that went through
// REQUEST_CHANGES. Carried inline rather than behind a separate endpoint because a moderator
// picking up a returned task needs to read what the previous round asked for before deciding
// it again.
public record ModerationTaskResponse(
        UUID id,
        UUID revisionId,
        ModerationTaskState state,
        UUID claimedBy,
        Instant claimedAt,
        Instant createdAt,
        Instant updatedAt,
        List<ModerationDecisionResponse> decisions) {
}
