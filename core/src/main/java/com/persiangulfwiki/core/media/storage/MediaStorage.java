package com.persiangulfwiki.core.media.storage;

import java.time.Duration;
import java.util.Optional;

// The only door to object storage. Nothing outside this package touches the AWS SDK, so moving
// from the self-hosted MinIO build to AWS S3 (or anything else S3-shaped) is a config change,
// and swapping the SDK is a change to one class. Multipart operations join this interface in
// the phase that needs them.
public interface MediaStorage {

    // Signing is local (no round trip to the store). The signature covers Content-Type,
    // Content-Length and x-amz-checksum-sha256, so the store itself rejects any other file.
    PresignedUpload presignPut(StorageBucket bucket, String key, String contentType, long bytes, String sha256Base64,
            Duration ttl);

    // The returned URL is a bearer credential for the object until it expires: never log it.
    // It also makes the store answer with Cache-Control: private, no-store, so neither the
    // browser nor a shared cache keeps a copy of a not-yet-public object past the URL's life.
    String presignGet(StorageBucket bucket, String key, Duration ttl);

    // Writes bytes the app already holds, overwriting any existing object. Every user-supplied file
    // goes through presignPut instead; this is for files the app ships itself (the dev demo library).
    void put(StorageBucket bucket, String key, byte[] bytes, String contentType);

    // As put, served with the given Cache-Control. Also for bytes the app produced itself (an
    // avatar re-encoded from the user's upload), never for a user's file as uploaded.
    void put(StorageBucket bucket, String key, byte[] bytes, String contentType, String cacheControl);

    // The whole object in memory, so only for objects whose size the caller has already bounded
    // (via head). Empty when no such object exists.
    Optional<byte[]> read(StorageBucket bucket, String key);

    // Server-side copy within one bucket; the bytes never pass through this app. The copy keeps
    // the source's Content-Type and is served with the given Cache-Control. Overwrites an
    // existing target, so repeating a copy is harmless. False, with nothing written, when the
    // source does not exist; any other storage failure propagates.
    boolean copy(StorageBucket bucket, String sourceKey, String targetKey, String cacheControl);

    // Empty when no such object exists; any other storage failure propagates.
    Optional<StoredObject> head(StorageBucket bucket, String key);

    // Idempotent: deleting a key that does not exist is not an error.
    void delete(StorageBucket bucket, String key);

    // Deletes every object whose key starts with prefix and returns how many there were.
    // Idempotent like delete. prefix must be non-blank and should end in "/": an empty one
    // would empty the bucket, so it is refused.
    int deletePrefix(StorageBucket bucket, String prefix);
}
