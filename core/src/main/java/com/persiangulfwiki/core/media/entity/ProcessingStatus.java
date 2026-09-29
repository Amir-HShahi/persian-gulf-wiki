package com.persiangulfwiki.core.media.entity;

// Where the *file* is in its lifecycle, independent of whether the public may see it (that is
// PublicationStatus). An item is public only when this is READY and that is PUBLISHED.
//
//   UPLOADING  -- reserved; the client holds a presigned PUT and has not confirmed yet.
//   PROCESSING -- the upload was verified against its declaration and a pipeline job is queued.
//   READY      -- variants exist in the media bucket; the item can be reviewed.
//   FAILED     -- terminal. ArticleMedia.failureCode says why, as a localisable code.
public enum ProcessingStatus {
    UPLOADING,
    PROCESSING,
    READY,
    FAILED
}
