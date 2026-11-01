package com.persiangulfwiki.core.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

// A declaration of the image the client is about to PUT straight to storage. All three fields are
// signed into the upload URL, so storage itself refuses any other file.
public record ReserveAvatarRequest(
        @NotBlank(message = "{validation.contentType.required}")
        @Size(max = 100, message = "{validation.contentType.tooLong}")
        @Schema(description = "image/jpeg or image/png.", example = "image/jpeg")
        String contentType,

        @NotNull(message = "{validation.mediaBytes.required}")
        @Positive(message = "{validation.mediaBytes.positive}")
        @Schema(description = "Exact file size in bytes, at most 5 MiB.", example = "182000")
        Long bytes,

        @NotBlank(message = "{validation.sha256.required}")
        @Pattern(regexp = "^[A-Za-z0-9+/]{43}=$", message = "{validation.sha256.format}")
        @Schema(description = "Base64 (standard alphabet, padded) SHA-256 of the file -- the x-amz-checksum-sha256 "
                + "encoding, 44 characters.", example = "wuaGgjSJztIBf2BZuLI5MYtjZPbc2DXQpRkQWh6t1uQ=")
        String sha256) {
}
