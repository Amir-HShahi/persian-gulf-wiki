-- Phase 3: editorial moderation. A moderation task is the unit of work a moderator picks up:
-- one revision, awaiting a policy judgement. The moderator judges *policy* (is this allowed
-- on the wiki at all), not subject-matter correctness -- that is Phase 5's ExpertReview,
-- which is advisory and never touches anything in this file.
--
-- The two tables are deliberately 1:0..* rather than 1:1. A single task can accumulate
-- several decisions over time, because REQUEST_CHANGES is non-terminal: the moderator sends
-- the revision back, the author fixes and resubmits, and the *same* task is decided again.
-- APPROVE and REJECT are terminal and end the task after one decision. That asymmetry is the
-- whole reason a decision is its own row rather than three columns on the task.

CREATE TABLE moderation_tasks (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    -- ON DELETE CASCADE, unlike most FKs in this schema, and it is load-bearing in two
    -- places. Correctness: a task is meaningless without the revision it judges, so it must
    -- never outlive one. Operations: articles -> article_translations -> article_revisions is
    -- already a CASCADE chain (V16), so without CASCADE here a task would block deletion of
    -- the whole article above it -- including DevTestArticleSweeper's hourly bulk delete,
    -- which would abort its entire pass on the first blocked row rather than skipping it.
    revision_id         UUID NOT NULL REFERENCES article_revisions (id) ON DELETE CASCADE,
    -- OPEN / CLAIMED / DECIDED. See ModerationTaskState for what each means and, in
    -- particular, why a task returns to OPEN after REQUEST_CHANGES rather than staying
    -- CLAIMED or going DECIDED.
    state               VARCHAR(20) NOT NULL,
    -- Null whenever state is OPEN -- including after a REQUEST_CHANGES decision hands the
    -- task back to the queue, which clears both of these. Whoever claims it next starts from
    -- a clean slate; the previous round's moderator is still recorded permanently on that
    -- round's moderation_decisions row, so nothing about the history is lost by clearing it
    -- here.
    --
    -- ON DELETE SET NULL is safe precisely because this is a transient pointer and not the
    -- accountability record: losing "who currently holds this" when an account is deleted
    -- costs nothing, and the alternative (RESTRICT) would let an unclaimed-but-referenced
    -- task block a user deletion for no benefit. Contrast moderation_decisions.moderator_id
    -- below, which is the accountability record and therefore must not be nullable.
    claimed_by          UUID REFERENCES users (id) ON DELETE SET NULL,
    claimed_at          TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Same role as articles.dev_marker (V16) / subjects.dev_marker (V15): non-null only on
    -- rows minted by the dev-profile fixture endpoint, which is what makes handing an
    -- arbitrary id to the matching DELETE route safe.
    dev_marker          VARCHAR(40),
    -- At most one task per revision, ever. This is not merely a de-duplication guard, it is
    -- the model: a REJECT is terminal and sends the author off to author a *brand new*
    -- revision (new revision_number), which gets its own task. So "another attempt" always
    -- means another revision, never a second task on the same one, and ModerationService's
    -- open-or-reopen logic can therefore be a plain findByRevisionId.
    CONSTRAINT uq_moderation_tasks_revision UNIQUE (revision_id),
    CONSTRAINT ck_moderation_tasks_state
        CHECK (state IN ('OPEN', 'CLAIMED', 'DECIDED')),
    -- A claim must carry a timestamp: claimed_by non-null with claimed_at null is a claim
    -- nothing downstream can interpret. Enforced here rather than only in the service so it
    -- holds for the dev-profile fixture endpoint too, which bypasses the service layer on
    -- purpose.
    --
    -- Deliberately a one-way implication and NOT the symmetric
    -- `(claimed_by IS NULL) = (claimed_at IS NULL)`, which is what this started as and which
    -- is actively wrong: claimed_by is ON DELETE SET NULL, so deleting a moderator who holds
    -- a claim nulls that column while claimed_at stays set -- exactly the row the symmetric
    -- form rejects. The delete then fails on the CHECK rather than succeeding, which would
    -- make any moderator account holding a live claim undeletable (and would abort
    -- DevTestUserSweeper's entire bulk pass, not just the blocked row). The surviving
    -- claimed_at on such a row is a harmless orphan: ModerationService.claim treats
    -- "claimed_by is null" as unclaimed regardless of state, so the task returns to the queue
    -- instead of being stranded in CLAIMED with nobody able to decide it.
    CONSTRAINT ck_moderation_tasks_claim_pair
        CHECK (claimed_by IS NULL OR claimed_at IS NOT NULL)
);

CREATE INDEX idx_moderation_tasks_state ON moderation_tasks (state);
CREATE INDEX idx_moderation_tasks_claimed_by ON moderation_tasks (claimed_by);
CREATE INDEX idx_moderation_tasks_dev_marker ON moderation_tasks (dev_marker) WHERE dev_marker IS NOT NULL;

-- One round of judgement on a task. Append-only and never updated: a decision is a historical
-- record of what a specific moderator concluded at a specific moment, so a later round adds a
-- row rather than editing this one. That is why there is no updated_at here (contrast
-- moderation_tasks above, whose state genuinely changes over its life).
CREATE TABLE moderation_decisions (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    task_id             UUID NOT NULL REFERENCES moderation_tasks (id) ON DELETE CASCADE,
    -- NOT NULL with the default RESTRICT, deliberately unlike moderation_tasks.claimed_by
    -- above: this is the accountability record for a decision that was actually made, and
    -- nulling it on account deletion would silently erase who made it rather than fail
    -- loudly. Same reasoning as article_revisions.author_id in V16 -- and the same
    -- operational consequence: a moderator account cannot be hard-deleted while its
    -- decisions exist, which is why the dev-profile fixture endpoint defaults to a seeded
    -- account the user sweeper can never reclaim.
    moderator_id        UUID NOT NULL REFERENCES users (id),
    -- APPROVE / REJECT / REQUEST_CHANGES. The CHECK list mirrors the Decision enum exactly,
    -- same maintenance contract as every other enum-backed column in this schema: a new value
    -- means a migration alongside the enum.
    decision            VARCHAR(20) NOT NULL,
    -- The moderator's note to the author. Optional for APPROVE (nothing to explain), and
    -- deliberately *not* made NOT NULL for REJECT/REQUEST_CHANGES at the database level
    -- either -- a CHECK tying requiredness to the decision value would be enforced here but
    -- invisible at the API boundary, where a missing reason should surface as a field
    -- validation error naming the field, not a constraint violation. ModerationService owns
    -- that rule; see DecideRequest.
    reason              TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_moderation_decisions_decision
        CHECK (decision IN ('APPROVE', 'REJECT', 'REQUEST_CHANGES'))
);

-- The task's decision history is read as a unit (oldest first) whenever a task is displayed,
-- so the index carries created_at rather than task_id alone.
CREATE INDEX idx_moderation_decisions_task ON moderation_decisions (task_id, created_at);
CREATE INDEX idx_moderation_decisions_moderator ON moderation_decisions (moderator_id);
