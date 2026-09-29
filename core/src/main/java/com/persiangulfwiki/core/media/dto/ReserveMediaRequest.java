package com.persiangulfwiki.core.media.dto;

import com.persiangulfwiki.core.media.entity.MediaKind;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

// A declaration of the file the client is about to PUT straight to storage -- the bytes never
// pass through the API. Which content types and how many bytes are allowed depends on `type`,
// a cross-field rule MediaUploadService enforces (UnsupportedMediaContentTypeException,
// MediaTooLargeException). All three of contentType/bytes/sha256 are then signed into the
// upload URL, so storage itself refuses any other file.
public record ReserveMediaRequest(
        @NotNull(message = "{validation.mediaType.required}")
        @Schema(description = "IMAGE, VIDEO or PANORAMA_360. A panorama is an equirectangular 360° image (2:1).")
        MediaKind type,

        @NotBlank(message = "{validation.contentType.required}")
        @Size(max = 100, message = "{validation.contentType.tooLong}")
        @Schema(description = "MIME type of the file. IMAGE: image/jpeg, image/png, image/webp. PANORAMA_360: "
                + "image/jpeg, image/png, image/webp. VIDEO: video/mp4, video/quicktime, video/webm.",
                example = "image/jpeg")
        String contentType,

        @NotNull(message = "{validation.mediaBytes.required}")
        @Positive(message = "{validation.mediaBytes.positive}")
        @Schema(description = "Exact file size in bytes. The upload must be exactly this long. Limits: IMAGE 25 MiB, "
                + "PANORAMA_360 95 MiB, VIDEO 95 MiB.", example = "3145728")
        Long bytes,

        @NotBlank(message = "{validation.sha256.required}")
        @Pattern(regexp = "^[A-Za-z0-9+/]{43}=$", message = "{validation.sha256.format}")
        @Schema(description = "Base64 (standard alphabet, padded) SHA-256 of the file -- the x-amz-checksum-sha256 "
                + "encoding, 44 characters.", example = "wuaGgjSJztIBf2BZuLI5MYtjZPbc2DXQpRkQWh6t1uQ=")
        String sha256,

        @Valid
        @Schema(description = "What is known about the shot. Optional, as is every field inside it.")
        MediaMetadataRequest metadata) {
}
