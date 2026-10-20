-- Moderated edits of everything a subject says about itself: its names, aliases, facts, geometry
-- and main image. One revision table covers all of it, so the moderation queue gains a single
-- new target column rather than one per kind of change. A revision with no subject yet is a
-- contributor's proposal for a new subject; approving it creates the subject.
--
-- The revision's payload (jsonb) is the source of truth. subject_facts, subject_aliases, the
-- SUBJECT labels and the subtype geometry column are a read model of the live revision's
-- payload, rewritten only by approval -- facet counts and the infobox read them, never jsonb.

CREATE TABLE subject_revisions (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    -- Null only on a proposal still waiting for approval (or rejected); approval fills it in.
    -- CASCADE for the V17 reasons: a revision is meaningless without its subject, and anything
    -- that deletes a subject (the dev sweeper, phase 7's hard delete) must not be blocked by its
    -- history.
    subject_id       UUID REFERENCES subjects (id) ON DELETE CASCADE,
    -- Copied from the subject on an edit and chosen by the author on a proposal. Kept on the row
    -- so a proposal, which has no subject to read it from, can still be validated.
    kind             VARCHAR(30) NOT NULL,
    -- 1-based per subject; a proposal is always 1. Allocated by SubjectRevisionService under a
    -- lock on the subject row, never client-supplied.
    revision_number  INT NOT NULL,
    -- The live revision an edit was copied from. Submitting or approving an edit whose base is
    -- no longer live is a 409: approving it would silently undo whatever was approved since.
    -- SET NULL rather than RESTRICT so deleting one revision (the dev sweeper) never blocks on
    -- another that was based on it; a null base on an edit simply reads as stale.
    base_revision_id UUID REFERENCES subject_revisions (id) ON DELETE SET NULL,
    payload          JSONB NOT NULL,
    summary          TEXT,
    -- Same lifecycle as article_revisions.status (V16).
    status           VARCHAR(25) NOT NULL,
    -- RESTRICT, same reasoning as article_revisions.author_id: it is the authorization key for
    -- editing a draft, so nulling it on account deletion would silently strip that check.
    author_id        UUID NOT NULL REFERENCES users (id),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Same role as subjects.dev_marker (V15).
    dev_marker       VARCHAR(40),
    CONSTRAINT uq_subject_revisions_subject_number UNIQUE (subject_id, revision_number),
    -- Target of subject_live_revisions' composite FK below.
    CONSTRAINT uq_subject_revisions_id_subject UNIQUE (id, subject_id),
    CONSTRAINT ck_subject_revisions_revision_number CHECK (revision_number >= 1),
    -- Mirrors SubjectKind, same maintenance contract as subjects.kind's CHECK.
    CONSTRAINT ck_subject_revisions_kind
        CHECK (kind IN ('ISLAND', 'PORT', 'OIL_FIELD', 'SPECIES', 'STRAIT', 'FISHING_GROUND', 'PROVINCE')),
    CONSTRAINT ck_subject_revisions_status
        CHECK (status IN ('DRAFT', 'PENDING', 'CHANGES_REQUESTED', 'APPROVED', 'REJECTED')),
    -- A proposal has nothing to be based on and nothing before it.
    CONSTRAINT ck_subject_revisions_proposal_shape
        CHECK (subject_id IS NOT NULL OR (base_revision_id IS NULL AND revision_number = 1)),
    -- Approving a proposal is what creates its subject, so an approved revision always has one.
    CONSTRAINT ck_subject_revisions_approved_has_subject
        CHECK (status <> 'APPROVED' OR subject_id IS NOT NULL)
);

CREATE INDEX idx_subject_revisions_author ON subject_revisions (author_id);
CREATE INDEX idx_subject_revisions_dev_marker ON subject_revisions (dev_marker) WHERE dev_marker IS NOT NULL;

-- Which revision readers currently see. A table of its own rather than a column on subjects,
-- which stays insert-only (see the Subject entity). A subject with no row here has never had a
-- revision approved -- one minted by the dev fixture route, or created before revisions existed.
CREATE TABLE subject_live_revisions (
    subject_id  UUID PRIMARY KEY REFERENCES subjects (id) ON DELETE CASCADE,
    revision_id UUID NOT NULL,
    approved_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_subject_live_revisions_revision UNIQUE (revision_id),
    -- Composite, so the live revision provably belongs to this subject rather than to whichever
    -- subject the service happened to pass. NO ACTION: a live revision cannot be deleted on its
    -- own, only together with its subject, whose CASCADE removes this row in the same statement.
    CONSTRAINT fk_subject_live_revisions_revision
        FOREIGN KEY (revision_id, subject_id) REFERENCES subject_revisions (id, subject_id)
);

-- The live revision's facts, one row per value, with the code resolved to an id. Deleted and
-- re-inserted for the whole subject by each approval, in the same transaction.
CREATE TABLE subject_facts (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    subject_id           UUID NOT NULL REFERENCES subjects (id) ON DELETE CASCADE,
    -- CASCADE on the vocabulary FKs: the API never deletes a definition or an option (codes are
    -- retired with is_deprecated, see V22), so only the dev attribute sweeper ever fires these,
    -- and RESTRICT would make it abort on the first minted field a fixture subject used.
    attribute_id         UUID NOT NULL REFERENCES attribute_definitions (id) ON DELETE CASCADE,
    -- The approved revision the value came from; a fact's own source is source_id below.
    revision_id          UUID NOT NULL REFERENCES subject_revisions (id) ON DELETE CASCADE,
    -- Position of the value within its field, in payload order.
    sort                 INT NOT NULL,
    value_number         NUMERIC,
    value_option_id      UUID REFERENCES attribute_options (id) ON DELETE CASCADE,
    -- NO ACTION: a subject another subject's fact points at must not vanish out from under it
    -- (phase 7's hard delete answers 409 for exactly this).
    value_ref_subject_id UUID REFERENCES subjects (id),
    value_bool           BOOLEAN,
    value_date           DATE,
    -- PLAIN_TEXT.
    value_text           TEXT,
    -- LOCALIZED_TEXT: {"fa": ..., "en": ..., "ar": ...}, Farsi required.
    value_localized      JSONB,
    -- When the value held, e.g. the census date of a population figure.
    as_of                DATE,
    -- PREFERRED / NORMAL. Only PREFERRED values show in the infobox and count in facets; NORMAL
    -- keeps e.g. an older census figure. A single-valued field has at most one PREFERRED value.
    rank                 VARCHAR(10) NOT NULL,
    -- SET NULL: sources have no delete route outside the dev sweeper, which must not be blocked
    -- by a fixture fact citing a minted source. The revision payload keeps the id regardless.
    source_id            UUID REFERENCES sources (id) ON DELETE SET NULL,
    CONSTRAINT ck_subject_facts_exactly_one_value
        CHECK (num_nonnulls(value_number, value_option_id, value_ref_subject_id, value_bool, value_date, value_text,
                            value_localized) = 1),
    CONSTRAINT ck_subject_facts_rank CHECK (rank IN ('PREFERRED', 'NORMAL'))
);

CREATE INDEX idx_subject_facts_subject ON subject_facts (subject_id);
-- The facet counts (phase 5) group by option or referenced subject within one field.
CREATE INDEX idx_subject_facts_attribute_option ON subject_facts (attribute_id, value_option_id, subject_id);
CREATE INDEX idx_subject_facts_attribute_ref ON subject_facts (attribute_id, value_ref_subject_id, subject_id);
CREATE INDEX idx_subject_facts_revision ON subject_facts (revision_id);

-- Other names a subject goes by, per language, for the duplicate lookup (phase 7). Replaced as
-- a whole by each approval.
CREATE TABLE subject_aliases (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    subject_id UUID NOT NULL REFERENCES subjects (id) ON DELETE CASCADE,
    -- Mirrors config/SupportedLocales.
    language   VARCHAR(2) NOT NULL,
    value      VARCHAR(200) NOT NULL,
    CONSTRAINT uq_subject_aliases_subject_language_value UNIQUE (subject_id, language, value),
    CONSTRAINT ck_subject_aliases_language CHECK (language IN ('fa', 'en', 'ar')),
    CONSTRAINT ck_subject_aliases_value_not_blank CHECK (btrim(value) <> '')
);

-- Moderation tasks gain a third target, following the V18 pattern exactly: one nullable FK
-- column per target type plus an exactly-one CHECK, CASCADE so a task goes with its revision,
-- and at most one task per revision ever (a rejected revision's next attempt is a new revision).
ALTER TABLE moderation_tasks
    ADD COLUMN subject_revision_id UUID REFERENCES subject_revisions (id) ON DELETE CASCADE;

ALTER TABLE moderation_tasks
    ADD CONSTRAINT uq_moderation_tasks_subject_revision UNIQUE (subject_revision_id);

ALTER TABLE moderation_tasks DROP CONSTRAINT ck_moderation_tasks_exactly_one_target;
ALTER TABLE moderation_tasks
    ADD CONSTRAINT ck_moderation_tasks_exactly_one_target
        CHECK (num_nonnulls(revision_id, media_metadata_version_id, subject_revision_id) = 1);
