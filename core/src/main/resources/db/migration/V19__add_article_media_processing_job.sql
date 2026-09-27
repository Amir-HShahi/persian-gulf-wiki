-- The pipeline job an item is currently waiting on. Written together with the PROCESSING
-- status by /complete, in the same transaction that publishes the job, and compared against
-- every result that comes back: a result is applied only when its job_id matches.
--
-- The status check alone ("ignore results for items not PROCESSING") is not enough, because it
-- cannot tell two jobs for the same item apart. That happens when the job was published but its
-- status commit then failed (see MediaJobPublisher): the item is back in UPLOADING, the client
-- calls /complete again, and a second job is queued. The first job's result must then be
-- ignored, and only its id says which one it is. Redelivery of an already-applied result is
-- covered by the status check -- the item has moved on to READY or FAILED.
--
-- Null for every item that never reached PROCESSING, and kept after the item leaves it, so a
-- late result can still be logged against the job it belongs to.
ALTER TABLE article_media ADD COLUMN processing_job_id UUID;

-- The stuck-processing and failed-item sweeps both select by status and the time the item
-- entered it, which is updated_at: nothing else writes a PROCESSING or FAILED row.
CREATE INDEX idx_article_media_processing_status_updated
    ON article_media (processing_status, updated_at);
