package com.persiangulfwiki.core.jobqueue;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;

// A finished job's `result` column: the worker's queue.Result, described for core's side in
// submission-pipeline/README.md and CORE_CHANGES.md. Field names are the worker's json tags, so
// the contract fixes them, not Java naming. Replaces the Redis-era PipelineResult.
//
// Only a `done` row carries one. A `failed` row usually has none -- its last_error says why --
// and the row's status, not anything in here, says which of the two a job ended as.
//
// Everything is nullable because nothing here is trusted until the feature reading it has
// checked it: this is another process's output, and a missing field has to become a refused
// result, not a NullPointerException halfway through applying it. Unknown properties are
// ignored so the worker can add fields (duration_ms, finished_at, a later job type's block)
// without core failing to read the row.
@JsonIgnoreProperties(ignoreUnknown = true)
public record JobResult(
        @Nullable Integer version,
        @JsonProperty("job_id") @Nullable Long jobId,
        @Nullable String type,
        @Nullable String outcome,
        @Nullable Integer attempt,
        @Nullable Reason reason,
        @Nullable Image image,
        @Nullable Normalize normalize,
        @Nullable Links links) {

    // The only result shape this core understands.
    public static final int CONTRACT_VERSION = 1;

    public static final String OUTCOME_OK = "ok";
    public static final String OUTCOME_REJECTED = "rejected";

    // detail is for the logs only, and its values are not all strings (a limit is a number).
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Reason(@Nullable String code, @Nullable Map<String, Object> detail) {
    }

    // The payload of an "ok" image or panorama job.
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Image(
            // As displayed, after EXIF rotation.
            @Nullable Integer width,
            @Nullable Integer height,
            @Nullable String format,
            // Standard padded base64 of the SHA-256 the worker computed -- the same encoding as
            // the job's declared_sha256.
            @Nullable String sha256,
            // A small data: URI. Left out when one could not be made small enough.
            @Nullable String placeholder,
            @Nullable Attribution attribution,
            @Nullable List<Variant> variants,
            @Nullable Original original) {
    }

    // What the original file says about its author and licence; every field optional.
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Attribution(
            @Nullable String author,
            @Nullable String credit,
            @Nullable String copyright,
            @Nullable String license,
            @JsonProperty("license_url") @Nullable String licenseUrl,
            // As the file wrote it, e.g. 2024-05-01T10:20:30, with no time zone.
            @JsonProperty("created_at") @Nullable String createdAt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Variant(
            // w320, w640, ... for an image; 2k, 4k, 8k for a panorama.
            @Nullable String label,
            @Nullable String format,
            @Nullable String key,
            @Nullable Long bytes,
            @Nullable Integer width,
            @Nullable Integer height) {
    }

    // The private copy of the upload under originals/{mediaId}/. Never served.
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Original(
            @Nullable String key,
            @Nullable Long bytes,
            @JsonProperty("content_type") @Nullable String contentType) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Normalize(
            @JsonProperty("revision_id") @Nullable String revisionId,
            @Nullable String locale,
            // Every finding, including those left out of findings.
            @Nullable Integer count,
            @Nullable List<Finding> findings,
            @JsonProperty("findings_truncated") @Nullable Integer findingsTruncated) {
    }

    // One suggested text fix. rule is an open list: new rules appear without notice. line and
    // column count in the revision's title, summary and body prose combined; the title is line 1.
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Finding(
            @Nullable String rule,
            @Nullable Integer line,
            @Nullable Integer column,
            @Nullable Integer offset,
            @Nullable String found,
            @Nullable String suggest,
            @Nullable String context) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Links(
            @JsonProperty("revision_id") @Nullable String revisionId,
            // Links checked.
            @Nullable Integer count,
            // broken plus unreachable; restricted is not counted.
            @Nullable Integer broken,
            @Nullable List<Link> links,
            // Links past the worker's per-revision cap, never checked.
            @JsonProperty("links_truncated") @Nullable Integer linksTruncated) {
    }

    // status: ok, redirected, broken, unreachable, restricted, blocked, unchecked. error names
    // why a link with no usable answer failed, and is an open list.
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Link(
            @Nullable String url,
            @Nullable String status,
            @JsonProperty("http_status") @Nullable Integer httpStatus,
            @JsonProperty("final_url") @Nullable String finalUrl,
            @JsonProperty("archive_url") @Nullable String archiveUrl,
            @Nullable String error) {
    }
}
