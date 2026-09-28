package com.persiangulfwiki.core.media.dto;

import com.persiangulfwiki.core.media.entity.MetadataVersionStatus;

import io.swagger.v3.oas.annotations.media.Schema;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// What a moderator needs to judge one metadata version: the item itself (files included, via
// signed URLs while it is not public) and the version under review in full -- every language's
// caption and every hotspot, including hotspots to targets that are not public yet, which the
// public views drop. `current` is the other side of the comparison for an edit: the approved
// version the proposal would replace, in the same full form.
public record MediaReviewResponse(
        @Schema(description = "The gallery item. Its `metadata`/`links` are its current approved version -- what the "
                + "public sees -- or, for an item never approved, its newest version, which is then usually the "
                + "one under review.")
        MediaResponse item,

        @Schema(description = "The metadata version this task judges.")
        MediaMetadataResponse proposed,

        MetadataVersionStatus proposedStatus,

        @Schema(description = "The account that proposed this version.")
        UUID submittedBy,

        Instant submittedAt,

        @Schema(description = "The proposed caption in every language it was written in, keyed by BCP-47 tag.")
        Map<String, String> descriptions,

        @Schema(description = "The proposed hotspots (360° panoramas only; empty otherwise), including those whose "
                + "target is not public -- their `target.smallestVariant` is null.")
        List<PanoramaLinkResponse> links,

        @Schema(description = "The item's current approved version in full, to compare the proposal against: "
                + "every caption and every hotspot, including hotspots to targets that are not public. Null "
                + "while the item has never been approved.", nullable = true)
        @Nullable MediaMetadataVersionResponse current) {
}
