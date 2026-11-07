package com.persiangulfwiki.core.media.storage;

// The two buckets, named by role rather than by their configured names so callers never read
// bucket config themselves -- S3MediaStorage resolves each to STORAGE_STAGING_BUCKET /
// STORAGE_MEDIA_BUCKET.
//
//   STAGING -- private. Holds each upload exactly as sent (EXIF and all) until the pipeline
//              has processed it; core deletes it once the item is READY, and the cleanup sweeps
//              delete it with any item that never gets there. The worker only ever reads here.
//   MEDIA   -- processed variants. Approved ones are served publicly (via
//              STORAGE_PUBLIC_BASE_URL); pending ones only through short-lived signed GETs.
//              Also the worker's private copy of each upload under originals/{mediaId}/, which
//              is never served -- it still carries the camera's metadata, location included.
public enum StorageBucket {
    STAGING,
    MEDIA
}
