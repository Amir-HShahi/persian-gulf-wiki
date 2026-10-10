-- The worker's job queue.
--
-- Like every schema object this belongs in core's Flyway migrations: the worker
-- only issues DML against it. Core enqueues by inserting a row; the worker
-- claims rows with FOR UPDATE SKIP LOCKED, runs them, and records the outcome
-- on the same row.
--
-- Rows are never deleted by the worker. How long finished and failed jobs are
-- kept is a retention decision for whoever owns the database.
CREATE TABLE IF NOT EXISTS jobs (
    id           bigserial   PRIMARY KEY,

    -- What to do. Free text on purpose: core may enqueue a type before the
    -- worker that handles it is deployed, and the worker parks an unknown type
    -- as failed rather than the database refusing the insert.
    type         text        NOT NULL,

    -- Everything the handler needs, so it never reads core's tables.
    payload      jsonb       NOT NULL,

    -- The row's lifecycle:
    --   pending  waiting for run_after, then for a free worker
    --   running  claimed; locked_until says how long that claim is good for
    --   done     finished, whatever the outcome. A rejected submission is a
    --            done job: the pipeline worked and the answer was no.
    --   failed   gave up after max_attempts, or could never run. Nothing
    --            retries it; it waits for a person.
    status       text        NOT NULL DEFAULT 'pending',

    -- attempts counts claims, so it rises on every claim, including the one
    -- that takes over a job whose worker died.
    attempts     integer     NOT NULL DEFAULT 0,

    -- How many claims this job gets. NULL means the worker's own default
    -- (WORKER_MAX_ATTEMPTS), so that setting can actually apply; core sets a
    -- number only when a job needs a different ceiling.
    max_attempts integer,

    -- Not claimable before this. Enqueue leaves it at now(); a retry pushes it
    -- into the future.
    run_after    timestamptz NOT NULL DEFAULT now(),

    -- Who holds the job and until when. A worker keeps extending locked_until
    -- while it works; if it dies, the lock lapses and the job becomes claimable
    -- again.
    locked_until timestamptz,
    locked_by    text,

    -- The worker's result, as JSON, once the job is done. Core reads this.
    result       jsonb,

    -- Why the last attempt failed, for a person looking at a stuck job.
    last_error   text,

    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT ck_jobs_status
        CHECK (status IN ('pending', 'running', 'done', 'failed')),
    CONSTRAINT ck_jobs_attempts
        CHECK (attempts >= 0 AND (max_attempts IS NULL OR max_attempts >= 1)),
    -- A claim is either whole or absent: a running job has an owner and an
    -- expiry, and every other job has neither.
    CONSTRAINT ck_jobs_claim
        CHECK ((status = 'running') = (locked_by IS NOT NULL AND locked_until IS NOT NULL))
);

-- The two ways a job becomes claimable, each with its own small index: due
-- pending jobs in the order they will be taken, and running jobs whose lock
-- has lapsed. Finished jobs are in neither, so the claim query never reads
-- them however many pile up.
CREATE INDEX IF NOT EXISTS idx_jobs_claimable
    ON jobs (run_after, id) WHERE status = 'pending';
CREATE INDEX IF NOT EXISTS idx_jobs_stale
    ON jobs (locked_until) WHERE status = 'running';

-- Wake the workers when a job is enqueued. pg_notify is delivered when the
-- inserting transaction commits, so a worker is never woken for a row it
-- cannot see yet. This only makes pickup quicker: a missed notification is
-- covered by the worker's own polling.
CREATE OR REPLACE FUNCTION jobs_notify() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM pg_notify('jobs', NEW.id::text);
    RETURN NULL;
END
$$;

DROP TRIGGER IF EXISTS trg_jobs_notify ON jobs;
CREATE TRIGGER trg_jobs_notify
    AFTER INSERT ON jobs
    FOR EACH ROW EXECUTE FUNCTION jobs_notify();
