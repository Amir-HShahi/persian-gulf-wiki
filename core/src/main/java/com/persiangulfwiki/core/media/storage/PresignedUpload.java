package com.persiangulfwiki.core.media.storage;

import java.time.Instant;
import java.util.Map;

// requiredHeaders are the signed headers the client must send on its PUT, verbatim. Host and
// Content-Length are signed too but left out: the browser sets both itself and a script may
// not (fetch silently drops a Content-Length it is handed), so the client's only obligation
// for those is to send exactly the declared number of bytes to exactly this URL.
public record PresignedUpload(String url, Instant expiresAt, Map<String, String> requiredHeaders) {
}
