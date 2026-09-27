package com.persiangulfwiki.core.dev.dto;

import com.persiangulfwiki.core.moderation.entity.Decision;
import com.persiangulfwiki.core.moderation.entity.ModerationTaskState;

import java.util.UUID;

// Every field is nullable, matching DevTestArticleRequest. At most one of revisionId and
// mediaMetadataVersionId may be sent (ck_moderation_tasks_exactly_one_target, V18); sending both
// is refused by that constraint as a 409. Sending neither mints a throwaway article whose first
// revision is PENDING and targets that -- its id comes back in the response. Pass a target only
// to judge a specific one, e.g. from POST /api/dev/test-articles or POST /api/dev/test-media.
//
// state is the point of this endpoint. The real flow only ever reaches CLAIMED by having a
// moderator call claim, and DECIDED by having that same moderator then call decide on a
// revision that is currently PENDING -- so "a task sitting at DECIDED" costs an E2E suite a
// whole submit -> claim -> decide sequence, plus an account holding the moderator role, before
// it can assert anything about how a decided task is displayed. Here it is one POST.
//
// claimedByUserId is read only when it can be honored without breaking
// ck_moderation_tasks_claim_pair -- see DevTestModerationController.resolveClaimantUserId for
// which states populate the claim pair and why OPEN never does.
//
// decision + reason seed one ModerationDecision row on the new task. The service's rule that
// REJECT and REQUEST_CHANGES must carry a reason is deliberately not reproduced: this endpoint
// bypasses ModerationService entirely, and a reasonless REJECT is exactly the sort of
// otherwise-unreachable row a fixture may want to assert a renderer copes with.
public record DevTestModerationRequest(
        UUID revisionId,
        ModerationTaskState state,
        UUID claimedByUserId,
        Decision decision,
        String reason,
        UUID mediaMetadataVersionId) {
}
