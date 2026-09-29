package com.persiangulfwiki.core.media.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record ReserveMediaResponse(
        @Schema(description = "The new media item. Poll GET .../media/{mediaId} for its status.")
        UUID mediaId,

        @Schema(description = "Presigned URL to PUT the file to, directly (not through the API). A bearer "
                + "credential until expiresAt: do not log or share it.")
        String uploadUrl,

        @Schema(description = "When uploadUrl stops working. Reserve again after this.")
        Instant expiresAt,

        @Schema(description = "Headers the PUT must carry, verbatim (content-type and x-amz-checksum-sha256; "
                + "names are lower-case). "
                + "Host and Content-Length are signed too but set by the browser itself; the body must "
                + "be exactly the declared number of bytes.")
        Map<String, String> requiredHeaders) {
}
