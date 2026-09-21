package com.persiangulfwiki.core.moderation.entity;

// What a moderator concluded in one round of review. Mirrored by
// ck_moderation_decisions_decision in V17 -- a new value here means a migration.
//
// The moderator is judging *policy* -- does this belong on the wiki, is it sourced, is it
// within scope -- not whether the facts are right. Subject-matter correctness is Phase 5's
// ExpertReview, which is advisory and cannot change any status here.
public enum Decision {

    // Revision -> APPROVED, task -> DECIDED, and the revision becomes its translation's
    // published content (ArticleTranslation.currentRevisionId now points at it).
    APPROVE,

    // Revision -> REJECTED, task -> DECIDED. Terminal for this revision: the author cannot
    // edit or resubmit it. Trying again means authoring a new revision, which gets its own
    // task (moderation_tasks.revision_id is unique).
    REJECT,

    // Revision -> CHANGES_REQUESTED, task back to OPEN rather than DECIDED. "Broadly fine,
    // needs a fix" -- the author edits the same revision in place and resubmits it, and the
    // same task is decided again. This is the only value that produces a second decision row
    // on one task.
    REQUEST_CHANGES
}
