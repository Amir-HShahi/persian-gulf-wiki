package com.persiangulfwiki.core.media.storage;

// The two buckets, named by role rather than by their configured names so callers never read
// bucket config themselves -- S3MediaStorage resolves each to STORAGE_STAGING_BUCKET /
// STORAGE_MEDIA_BUCKET.
//
//   STAGING -- private. Holds each original exactly as uploaded (EXIF and all) until the
//              pipeline has produced variants from it, then the pipeline deletes it.
//   MEDIA   -- processed variants. Approved ones are served publicly (via
//              STORAGE_PUBLIC_BASE_URL); pending ones only through short-lived signed GETs.
public enum StorageBucket {
    STAGING,
    MEDIA
}
