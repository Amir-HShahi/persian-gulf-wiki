package com.persiangulfwiki.core.subject.dto;

import com.persiangulfwiki.core.media.dto.MediaVariantResponse;

import io.swagger.v3.oas.annotations.media.Schema;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.UUID;

// The image picked for the subject in its live revision, with what it takes to show it. Absent
// when none was picked or the image is no longer public, so every URL here is a public one.
@Schema(description = "The subject's main image, ready to show. Only ever a public image, so every URL is permanent.")
public record SubjectMainImageResponse(
        @Schema(description = "The image's id in the gallery of the article it belongs to.")
        UUID mediaId,

        @Schema(description = "A tiny inline preview image (a data: URI) to show, blurred, while a rendition loads. "
                + "May be null.", nullable = true)
        @Nullable String placeholder,

        @Schema(description = "Every rendition of the image, e.g. 640 and 1280 wide. Build `srcset` from these and "
                + "pick by `width` (the 640 one suits a listing card). The URLs never change and may be cached "
                + "indefinitely.")
        List<MediaVariantResponse> variants) {
}
