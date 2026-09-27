package com.persiangulfwiki.core.media.storage;

import org.jspecify.annotations.Nullable;

// sha256 is the store's own base64 SHA-256 of the object, present when the upload carried an
// x-amz-checksum-sha256 (every presigned PUT this app signs does). Null means the store did not
// record one, not that the object is empty.
public record StoredObject(long bytes, @Nullable String sha256) {
}
