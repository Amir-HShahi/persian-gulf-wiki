package com.persiangulfwiki.core.jobqueue;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// Plays the Go worker against the real jobs table. Core only ever reads a job's status, result
// and last_error back, so finishing one is a single UPDATE -- exactly what the worker's own
// "record the outcome on the same row" amounts to. No worker runs in any test, so a job core
// enqueues stays pending until a test finishes it here.
//
// Plain JDBC rather than QueueJobRepository so a test reads the row as the worker would, without
// going through the entity core maps it with.
public final class JobQueueTestSupport {

    private JobQueueTestSupport() {
    }

    // payload is the row's jsonb as text, for JsonPath.
    public record QueuedJob(long id, String type, String status, String payload) {
    }

    public static List<QueuedJob> jobsWhere(JdbcTemplate jdbcTemplate, String payloadField, String value) {
        return jdbcTemplate.query("""
                select id, type, status, cast(payload as text) as payload from jobs
                where payload ->> ? = ? order by id
                """, (row, _) -> new QueuedJob(row.getLong("id"), row.getString("type"), row.getString("status"),
                row.getString("payload")), payloadField, value);
    }

    // Every job enqueued for one gallery item (its payload's submission_id).
    public static List<QueuedJob> jobsForMedia(JdbcTemplate jdbcTemplate, UUID mediaId) {
        return jobsWhere(jdbcTemplate, "submission_id", mediaId.toString());
    }

    // Every job enqueued for one article revision (normalize and links, one pair per submit).
    public static List<QueuedJob> jobsForRevision(JdbcTemplate jdbcTemplate, UUID revisionId) {
        return jobsWhere(jdbcTemplate, "revision_id", revisionId.toString());
    }

    public static @Nullable Long processingJobIdOf(JdbcTemplate jdbcTemplate, UUID mediaId) {
        return jdbcTemplate.queryForObject("select processing_queue_job_id from article_media where id = ?",
                Long.class, mediaId);
    }

    public static void finish(JdbcTemplate jdbcTemplate, long jobId, String status, @Nullable String resultJson,
            @Nullable String lastError) {
        int updated = jdbcTemplate.update("""
                update jobs set status = ?, result = cast(? as jsonb), last_error = ?, updated_at = now()
                where id = ?
                """, status, resultJson, lastError, jobId);
        if (updated != 1) {
            throw new IllegalStateException("no jobs row " + jobId);
        }
    }

    // The worker's result envelope, without the type-specific block.
    public static Map<String, Object> envelope(long jobId, String type, String outcome) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("version", 1);
        result.put("job_id", jobId);
        result.put("type", type);
        result.put("outcome", outcome);
        result.put("attempt", 1);
        result.put("duration_ms", 812);
        result.put("finished_at", "2026-10-12T09:30:00Z");
        return result;
    }
}
