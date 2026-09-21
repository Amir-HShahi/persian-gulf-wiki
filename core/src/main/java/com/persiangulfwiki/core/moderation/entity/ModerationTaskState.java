package com.persiangulfwiki.core.moderation.entity;

// Where a task sits in the moderator queue. Mirrored by ck_moderation_tasks_state in V17 --
// a new value here means a migration.
public enum ModerationTaskState {

    // In the queue, claimable by any moderator. A task is OPEN both before it has ever been
    // looked at and again after a REQUEST_CHANGES decision hands it back -- the two are
    // deliberately not distinguished here, so the queue stays a flat "things that still need
    // a moderator eventually" list rather than growing a state per waiting-on-whom.
    //
    // The gap that leaves -- a task sitting OPEN while its revision is CHANGES_REQUESTED and
    // the author has not resubmitted yet -- is closed in ModerationService.decide, which
    // refuses to record a decision unless the revision is actually PENDING. So such a task
    // can be claimed, but not acted on until the author resubmits. See that method.
    OPEN,

    // A specific moderator holds it and is the only one who may decide it (see
    // NotTaskClaimantException). claimedBy/claimedAt are non-null exactly in this state.
    CLAIMED,

    // Terminal. Reached only by an APPROVE or a REJECT; a REQUEST_CHANGES never lands here,
    // which is the whole reason a task can accumulate more than one decision row.
    DECIDED
}
