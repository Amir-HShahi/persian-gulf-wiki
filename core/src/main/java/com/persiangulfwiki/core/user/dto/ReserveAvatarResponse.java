package com.persiangulfwiki.core.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record ReserveAvatarResponse(
        @Schema(description = "Pass to POST /api/users/me/avatar/{avatarId}/complete after the upload.")
        UUID avatarId,

        @Schema(description = "Presigned URL to PUT the file to, directly (not through the API). A bearer "
                + "credential until expiresAt: do not log or share it.")
        String uploadUrl,

        @Schema(description = "When uploadUrl stops working. Reserve again after this.")
        Instant expiresAt,

        @Schema(description = "Headers the PUT must carry, verbatim (content-type and x-amz-checksum-sha256). "
                + "Host and Content-Length are set by the browser; the body must be exactly the declared bytes.")
        Map<String, String> requiredHeaders) {
}
