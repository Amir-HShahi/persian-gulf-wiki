package com.persiangulfwiki.core.media.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record MediaVariantResponse(
        @Schema(description = "The rendition's label, e.g. \"640\", \"1280\", \"2k\", \"4k\", \"8k\". Pick by `width`, not by "
                + "parsing this.", example = "1280")
        String size,

        @Schema(description = "Encoding: \"jpeg\", \"webp\", \"ktx2\", or (later) \"hls\".", example = "webp")
        String format,

        @Schema(description = "Where to fetch it. For an approved, public item this is a permanent public URL whose "
                + "content never changes, so it may be cached indefinitely. For an item not yet public it is "
                + "a signed URL that expires within minutes: do not store or share it, and fetch the item again "
                + "for a fresh one. Load signed URLs with no referrer (e.g. `referrerpolicy=\"no-referrer\"`).")
        String url,

        @Schema(description = "Size of the file in bytes.", example = "151022")
        long bytes,

        @Schema(description = "Pixel width.", example = "1280")
        int width,

        @Schema(description = "Pixel height.", example = "960")
        int height) {
}
