package com.persiangulfwiki.core.media.dto;

import com.persiangulfwiki.core.media.entity.MetadataVersionStatus;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// One metadata version in full: its fields, every language's caption and its hotspots. What a
// proposed edit returns, what the version history lists, and the two sides of a moderator's
// comparison (MediaReviewResponse).
public record MediaMetadataVersionResponse(
        MediaMetadataResponse metadata,

        @Schema(description = "PENDING_REVIEW until a moderator decides; then APPROVED or REJECTED. A pending "
                + "version is also REJECTED, without a moderator judging it, when a newer version of the same item "
                + "is approved or when the item itself is rejected.")
        MetadataVersionStatus status,

        @Schema(description = "The account that proposed this version.")
        UUID submittedBy,

        Instant submittedAt,

        @Schema(description = "The caption in every language this version has one in, keyed by BCP-47 tag.")
        Map<String, String> descriptions,

        @Schema(description = "This version's hotspots (360° panoramas only; empty otherwise), limited to targets "
                + "the caller may see. A target that is not public carries no preview (`smallestVariant` is "
                + "null).")
        List<PanoramaLinkResponse> links) {
}
