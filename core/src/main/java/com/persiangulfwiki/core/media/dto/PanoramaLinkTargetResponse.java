package com.persiangulfwiki.core.media.dto;

import com.persiangulfwiki.core.media.entity.MediaKind;

import io.swagger.v3.oas.annotations.media.Schema;

import org.jspecify.annotations.Nullable;

import java.util.UUID;

// Just enough of the target to draw a hotspot preview and to navigate: the viewer fetches the
// full target item by mediaId/articleId when the hotspot is followed.
public record PanoramaLinkTargetResponse(
        UUID mediaId,

        @Schema(description = "The article the target belongs to, which may differ from the source's.")
        UUID articleId,

        @Schema(description = "The target's type. Normally PANORAMA_360.")
        MediaKind type,

        @Schema(description = "The target's tiny inline preview image (a data: URI), if it has one.", nullable = true)
        @Nullable String placeholder,

        @Schema(description = "The target's smallest rendition, for a hotspot thumbnail. Null only in the moderation "
                + "review, and there it means the target is not public: such a link is not shown anywhere else "
                + "until the target is approved.", nullable = true)
        @Nullable MediaVariantResponse smallestVariant) {
}
