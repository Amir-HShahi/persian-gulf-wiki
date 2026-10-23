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

    // One line per value; shown wherever this type appears in the API docs.
    public static final String VALUES_DOC = """
            - `upload_missing`: nothing was uploaded.
            - `upload_mismatch`: the uploaded file's size or checksum differs from what was declared.
            - `duplicate`: the same file is already in this article's gallery.
            - `unsupported_format`: the file's format isn't accepted.
            - `corrupt_file`: the file couldn't be read.
            - `dimensions_too_large`: the image is wider or taller than allowed.
            - `checksum_mismatch`: the file changed between upload and processing.
            - `panorama_aspect_ratio`: a 360° panorama must be twice as wide as it is tall.
            - `rejected`: the file was refused for a reason with no more specific code. Treat an unknown code like \
            this one.
            - `processing_error`: processing broke down; the file itself may be fine.
            - `processing_timeout`: processing didn't finish in time.""";

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
