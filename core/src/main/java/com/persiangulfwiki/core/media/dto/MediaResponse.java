package com.persiangulfwiki.core.media.dto;

import com.persiangulfwiki.core.media.entity.MediaKind;
import com.persiangulfwiki.core.media.entity.ProcessingStatus;
import com.persiangulfwiki.core.media.entity.PublicationStatus;

import io.swagger.v3.oas.annotations.media.Schema;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

// One gallery item, in the same shape everywhere: the public gallery list, the single-item read
// (which is also the uploader's status poll), the upload confirmation and the moderation review.
// The lifecycle fields only say something interesting to the uploader and moderators -- in the
// public list every item is READY/PUBLISHED with no failure.
//
// variants' URLs are the one thing that differs by who may see the item: public URLs for a
// public item, short-lived signed ones otherwise (see MediaVariantService).
public record MediaResponse(
        UUID id,
        UUID articleId,
        MediaKind type,

        @Schema(description = "UPLOADING (waiting for the PUT and /complete), PROCESSING, READY, or FAILED.")
        ProcessingStatus processingStatus,

        @Schema(description = "PENDING (not yet approved), PUBLISHED, REJECTED, or HIDDEN.")
        PublicationStatus publicationStatus,

        @Schema(description = "Set only when processingStatus is FAILED: a stable code to branch on. "
                + "Checked at upload confirmation: upload_missing (nothing was uploaded), upload_mismatch "
                + "(the stored file's size or checksum differs from the declaration). Set by processing: "
                + "duplicate (the same file is already in this article's gallery), unsupported_format, "
                + "corrupt_file, dimensions_too_large, checksum_mismatch, panorama_aspect_ratio (a 360° "
                + "panorama must be twice as wide as it is tall), rejected (the file was refused for a "
                + "reason with no more specific code), processing_error (processing broke down; the file "
                + "itself may be fine), processing_timeout (processing did not finish in time). New codes "
                + "may be added; treat an unknown one like rejected.", nullable = true)
        @Nullable String failureCode,

        @Schema(description = "Set exactly when failureCode is: a message explaining the failure to the uploader, in "
                + "the request's language.", nullable = true)
        @Nullable String failureMessage,

        @Schema(description = "A tiny inline preview image (a data: URI) to show while a rendition loads. Null until "
                + "processing has finished, and possibly afterwards.", nullable = true)
        @Nullable String placeholder,

        @Schema(description = "The processed renditions, e.g. several widths of an image, or 2K/4K/8K textures of a "
                + "360° panorama. Empty until processing has finished (READY).")
        List<MediaVariantResponse> variants,

        @Schema(description = "What is said about the item: when and where it was shot and which way it faced. The "
                + "approved version once the item is published; for an item not yet approved (visible only to its "
                + "uploader and moderators), the version sent with the upload. A later edit awaiting review is "
                + "never shown here.", nullable = true)
        @Nullable MediaMetadataResponse metadata,

        @Schema(description = "The caption in the language asked for with `language`. Null when no `language` was "
                + "given or the item has no caption in it -- there is no fallback to another language.",
                nullable = true)
        @Nullable String description,

        @Schema(description = "Clickable hotspots leading to other 360° panoramas, possibly in other articles. Only "
                + "for 360° panoramas (always empty otherwise), and only hotspots whose target is itself public: "
                + "the others reappear once their target is approved.")
        List<PanoramaLinkResponse> links,

        @Schema(description = "When a moderator approved the item. The public gallery is sorted by this, newest "
                + "first.", nullable = true)
        @Nullable Instant publishedAt,

        Instant createdAt,
        Instant updatedAt) {
}
