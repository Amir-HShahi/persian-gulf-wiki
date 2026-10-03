-- An expert reviewer's mark that a published subject revision is semantically correct. Advisory
-- only: moderators publish on policy alone and never wait for one, and a mark cannot be taken
-- back or turned into an "incorrect" flag. It belongs to the revision it was made on, so once a
-- newer revision is approved the subject reads as STALE ("verified r3, current r4") until an
-- expert verifies the new one -- nothing here is rewritten when the live pointer moves.
CREATE TABLE expert_verifications (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    -- CASCADE for the V24 reasons: a mark means nothing without its revision, and deleting a
    -- subject (which takes its revisions) must not be blocked by the marks on them. This is also
    -- what reclaims a dev fixture mark: the fixture route only marks revisions it minted.
    subject_revision_id UUID NOT NULL REFERENCES subject_revisions (id) ON DELETE CASCADE,
    -- NOT NULL with the default RESTRICT, like moderation_decisions.moderator_id (V17): this is
    -- who vouched for the revision, and nulling or dropping it on account deletion would silently
    -- change what readers are told about a subject.
    expert_id           UUID NOT NULL REFERENCES users (id),
    verified_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Same role as subjects.dev_marker (V15).
    dev_marker          VARCHAR(40),
    -- Also serves every lookup by revision (the verification state and the queue's NOT EXISTS).
    CONSTRAINT uq_expert_verifications_revision_expert UNIQUE (subject_revision_id, expert_id)
);

-- For expert_id's RESTRICT check when a user row is deleted.
CREATE INDEX idx_expert_verifications_expert ON expert_verifications (expert_id);
