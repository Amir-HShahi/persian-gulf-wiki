package com.persiangulfwiki.core.media.pipeline;

import com.persiangulfwiki.core.media.entity.ArticleMedia;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

// Puts one processing job for a verified upload on the Redis stream the Go submission-pipeline
// consumes. The field set below is the wire contract that the worker's queue.Job must read,
// documented for the worker's side in docs/MEDIA_PIPELINE.md: a field added here and not there
// is silently ignored by the worker, and one renamed on either side alone breaks every queued
// job. Change the two together.
// A stream entry is a flat string->string map, so declared_bytes travels as a decimal string.
//
// Called inside MediaUploadService.complete's transaction, *before* the PROCESSING status
// commits, and that ordering is deliberate. If XADD fails, the exception rolls the status back
// to UPLOADING and the client can simply call /complete again. The opposite order (commit, then
// publish) would leave an item stuck in PROCESSING with no job behind it whenever the publish
// failed. The price is the reverse window: a job whose status commit then fails. The worker's
// result carries a job_id the item does not hold (processing_job_id, V19), so MediaProcessingService
// discards it, and that job's only cost is wasted processing.
@Slf4j
@Component
@RequiredArgsConstructor
public class MediaJobPublisher {

    // Bumped only for a breaking change to the field set, so the worker can refuse a job
    // shape it does not understand rather than misreading it.
    static final String CONTRACT_VERSION = "1";

    private final StringRedisTemplate redisTemplate;

    @Value("${app.media.job-stream}")
    private final String jobStream;

    // Returns the job id, which the caller stores on the item: results are matched against it.
    public UUID publish(ArticleMedia media, String stagingKey) {
        UUID jobId = UUID.randomUUID();
        Map<String, String> fields = Map.of(
                "version", CONTRACT_VERSION,
                "job_id", jobId.toString(),
                "type", MediaJobType.of(media.getType()).wireName(),
                "submission_id", media.getId().toString(),
                "object_key", stagingKey,
                "content_type", media.getDeclaredContentType(),
                "declared_bytes", Long.toString(media.getDeclaredBytes()),
                "declared_sha256", media.getDeclaredSha256());
        RecordId recordId = redisTemplate.opsForStream().add(StreamRecords.string(fields).withStreamKey(jobStream));
        log.info("queued {} job {} for media {} (stream entry {})", fields.get("type"), jobId, media.getId(), recordId);
        return jobId;
    }
}
