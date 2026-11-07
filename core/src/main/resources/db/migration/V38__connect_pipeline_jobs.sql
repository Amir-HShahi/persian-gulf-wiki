-- Points the gallery and article revisions at the worker's jobs table (V37). Core inserts a job
-- row in the same transaction as the change that needs it, and reads the outcome back from the
-- same row; see docs/MEDIA_PIPELINE.md.
--
-- Both links below are ON DELETE SET NULL because the worker deletes finished job rows once
-- they are old enough (30 days for done, 90 for failed). By then core has copied what it keeps:
-- the item's variants and attribution, a revision's findings.

-- The jobs row an item in PROCESSING waits on. Replaces processing_job_id (V19), a UUID that
-- results on the old Redis stream were matched against; a jobs row's id is the match now, and
-- the poll joins on it. An item still PROCESSING when this runs was queued on that stream, so
-- nothing will ever answer it: the stuck-processing sweep fails it as processing_timeout.
-- Null for an item that never reached PROCESSING, and for a VIDEO, which has no job type yet.
ALTER TABLE article_media DROP COLUMN processing_job_id;
ALTER TABLE article_media ADD COLUMN processing_queue_job_id BIGINT REFERENCES jobs (id) ON DELETE SET NULL;
-- Serves both the result poll's join and the SET NULL the worker's clean-up triggers.
CREATE INDEX idx_article_media_processing_queue_job
    ON article_media (processing_queue_job_id) WHERE processing_queue_job_id IS NOT NULL;

-- What the file's own metadata (XMP, else EXIF) says about who made it and on what terms, as
-- the worker read it from the original before stripping it. A claim anyone can type into a
-- file, shown to moderators as a hint next to the uploader's declaration -- never the licence.
-- Null when the file says nothing.
ALTER TABLE article_media ADD COLUMN attribution JSONB;

-- The worker's advisory checks on a submitted revision, one row per check: text fixes
-- (NORMALIZE) and dead links (LINKS). Every submit -- the first and each resubmit after
-- REQUEST_CHANGES -- resets both rows to PENDING with a new job, replacing the previous findings.
-- Nothing here gates a moderation decision.
CREATE TABLE revision_checks (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    -- CASCADE: findings about a revision that no longer exists describe nothing.
    revision_id   UUID NOT NULL REFERENCES article_revisions (id) ON DELETE CASCADE,
    check_type    VARCHAR(20) NOT NULL,
    status        VARCHAR(20) NOT NULL,
    -- The job whose result this row is waiting for; a result from any other job (one queued by
    -- an earlier submit) is ignored.
    job_id        BIGINT REFERENCES jobs (id) ON DELETE SET NULL,
    -- The worker's refusal code (REJECTED only), e.g. revision_missing or unsupported_locale.
    reason_code   VARCHAR(60),
    -- DONE only. NORMALIZE: every finding / the same / findings left out of `findings`.
    -- LINKS: links checked / broken or unreachable / links past the worker's cap, never checked.
    total_count   INTEGER,
    problem_count INTEGER,
    omitted_count INTEGER,
    -- DONE only: the findings as core validated them, capped (see RevisionCheckService).
    findings      JSONB,
    finished_at   TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Same role as articles.dev_marker (V16).
    dev_marker    VARCHAR(40),
    CONSTRAINT uq_revision_checks_revision_type UNIQUE (revision_id, check_type),
    CONSTRAINT ck_revision_checks_type CHECK (check_type IN ('NORMALIZE', 'LINKS')),
    CONSTRAINT ck_revision_checks_status CHECK (status IN ('PENDING', 'DONE', 'FAILED', 'REJECTED'))
);

-- The result poll's join and the worker clean-up's SET NULL, as on article_media above.
CREATE INDEX idx_revision_checks_job ON revision_checks (job_id) WHERE job_id IS NOT NULL;
CREATE INDEX idx_revision_checks_dev_marker ON revision_checks (dev_marker) WHERE dev_marker IS NOT NULL;
