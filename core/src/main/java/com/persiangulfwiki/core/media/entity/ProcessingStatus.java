package com.persiangulfwiki.core.media.entity;

import io.swagger.v3.oas.annotations.media.Schema;

// Where the *file* is in its lifecycle, independent of whether the public may see it (that is
// PublicationStatus). An item is public only when this is READY and that is PUBLISHED.
//
//   UPLOADING  -- reserved; the client holds a presigned PUT and has not confirmed yet.
//   PROCESSING -- the upload was verified against its declaration and a pipeline job is queued.
//   READY      -- variants exist in the media bucket; the item can be reviewed.
//   FAILED     -- terminal. ArticleMedia.failureCode says why, as a localisable code.
@Schema(description = ProcessingStatus.VALUES_DOC)
public enum ProcessingStatus {
    UPLOADING,
    PROCESSING,
    READY,
    FAILED;

    // One line per value; shown wherever this type appears in the API docs.
    public static final String VALUES_DOC = """
            - `UPLOADING`: reserved; waiting for the file upload and its confirmation.
            - `PROCESSING`: the upload was verified and its renditions are being made.
            - `READY`: the renditions exist; the item can be reviewed and shown.
            - `FAILED`: final; `failureCode` says why.""";
}
