package com.persiangulfwiki.core.dev.dto;

import com.persiangulfwiki.core.moderation.entity.ModerationTaskState;

import java.util.UUID;

// Carries enough of the minted row that a test can drive the real /api/moderation endpoints
// afterward (e.g. POST .../tasks/{taskId}/decide as claimedByUserId) without a second lookup.
//
// decisionId is null unless a decision was requested -- the common fixture is a bare task with
// no history, and a caller that did ask for one needs its id to assert the history renders.
public record DevTestModerationResponse(
        UUID taskId,
        UUID revisionId,
        ModerationTaskState state,
        UUID claimedByUserId,
        UUID decisionId) {
}
