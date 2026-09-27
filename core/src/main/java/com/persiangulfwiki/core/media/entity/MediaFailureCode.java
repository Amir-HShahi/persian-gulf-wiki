package com.persiangulfwiki.core.media.entity;

import java.util.Arrays;
import java.util.Optional;

// Every value article_media.failure_code can hold, and the message key each one is shown with.
// The code is what the API returns and the frontend may branch on; the message is resolved by
// core in the caller's locale, so every code here needs a key in all three bundles.
//
// Two groups. The first is decided by core itself. The second is the pipeline's rejection
// reasons (Result.reason.code), and it is a contract with the Go worker, listed in
// docs/MEDIA_PIPELINE.md: a code the worker sends that is not here is stored as REJECTED (the
// generic one), so adding a pipeline code means adding it here, to the bundles and to that
// document first.
public enum MediaFailureCode {
    // Core: /complete found nothing in staging, or something other than what was declared.
    UPLOAD_MISSING("upload_missing", "media.failure.uploadMissing"),
    UPLOAD_MISMATCH("upload_mismatch", "media.failure.uploadMismatch"),
    // Core: the pipeline's verified hash matches another live item in the same article.
    DUPLICATE("duplicate", "media.failure.duplicate"),
    // Core: the worker gave up (status "failed"), or sent a result core could not accept.
    PROCESSING_ERROR("processing_error", "media.failure.processingError"),
    // Core: no result arrived within app.media.processing-timeout-minutes.
    PROCESSING_TIMEOUT("processing_timeout", "media.failure.processingTimeout"),
    // Core: stands in for a pipeline rejection code core does not know.
    REJECTED("rejected", "media.failure.rejected"),

    // Pipeline rejection codes.
    UNSUPPORTED_FORMAT("unsupported_format", "media.failure.unsupportedFormat", true),
    CORRUPT_FILE("corrupt_file", "media.failure.corruptFile", true),
    DIMENSIONS_TOO_LARGE("dimensions_too_large", "media.failure.dimensionsTooLarge", true),
    CHECKSUM_MISMATCH("checksum_mismatch", "media.failure.checksumMismatch", true),
    PANORAMA_ASPECT_RATIO("panorama_aspect_ratio", "media.failure.panoramaAspectRatio", true);

    private final String code;
    private final String messageKey;
    private final boolean isPipelineCode;

    MediaFailureCode(String code, String messageKey) {
        this(code, messageKey, false);
    }

    MediaFailureCode(String code, String messageKey, boolean isPipelineCode) {
        this.code = code;
        this.messageKey = messageKey;
        this.isPipelineCode = isPipelineCode;
    }

    public String code() {
        return code;
    }

    public String messageKey() {
        return messageKey;
    }

    public static Optional<MediaFailureCode> fromCode(String code) {
        return Arrays.stream(values()).filter(value -> value.code.equals(code)).findFirst();
    }

    // Only the second group: a worker may not claim one of core's own codes (e.g. "duplicate"),
    // which would make its rejection indistinguishable from a decision core made.
    public static Optional<MediaFailureCode> fromPipelineCode(String code) {
        return fromCode(code).filter(value -> value.isPipelineCode);
    }
}
