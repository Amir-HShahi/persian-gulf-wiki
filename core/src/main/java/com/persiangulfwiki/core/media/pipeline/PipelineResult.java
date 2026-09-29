package com.persiangulfwiki.core.media.pipeline;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;

// One result entry from the submission-results stream: the Go worker's queue.Result, as JSON in
// the entry's "result" field. The field names are the worker's json tags, so they are fixed by
// the contract in docs/MEDIA_PIPELINE.md, not by Java naming.
//
// Everything is nullable because nothing here is trusted until MediaProcessingService has
// checked it: this is input from another process, and a missing field has to be a rejected
// result, not a NullPointerException halfway through applying it. Unknown properties are
// ignored so the worker can add fields (its tabular/geo payloads, a later video one) without
// core failing to read the entry.
@JsonIgnoreProperties(ignoreUnknown = true)
public record PipelineResult(
        @Nullable Integer version,
        @JsonProperty("job_id") @Nullable String jobId,
        @JsonProperty("submission_id") @Nullable String submissionId,
        @Nullable String type,
        @Nullable String status,
        @Nullable Integer attempt,
        @Nullable Reason reason,
        // Operator-facing only; never shown to the contributor.
        @Nullable String error,
        @Nullable Media media) {

    public static final String STATUS_OK = "ok";
    public static final String STATUS_REJECTED = "rejected";
    public static final String STATUS_FAILED = "failed";

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Reason(@Nullable String code, @Nullable Map<String, String> detail) {
    }

    // The payload of an "ok" result for a media.* job.
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Media(
            // Standard padded base64 of the recomputed SHA-256 -- the same encoding as the job's
            // declared_sha256.
            @Nullable String sha256,
            @Nullable Integer width,
            @Nullable Integer height,
            // A small data: URI, stored and served as-is.
            @Nullable String placeholder,
            @Nullable List<Variant> variants) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Variant(
            @Nullable String size,
            @Nullable String format,
            @Nullable String key,
            @Nullable Long bytes,
            @Nullable Integer width,
            @Nullable Integer height) {
    }
}
