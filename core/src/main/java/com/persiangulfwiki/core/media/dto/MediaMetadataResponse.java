package com.persiangulfwiki.core.media.dto;

import com.persiangulfwiki.core.media.entity.HeadingReference;

import io.swagger.v3.oas.annotations.media.Schema;

import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

// One metadata version's fields. Every one of them may be null: a contributor may leave any of
// them out, and nothing is ever guessed to fill one in.
public record MediaMetadataResponse(
        @Schema(description = "The metadata version these fields come from.")
        UUID versionId,

        @Schema(description = "1 for the metadata sent with the upload, counting up with each later edit.", example = "1")
        int versionNumber,

        @Schema(description = "When the shot was taken (UTC).", nullable = true, example = "2026-04-12T06:30:00Z")
        @Nullable Instant shotAt,

        @Schema(description = "UTC offset in force where the shot was taken, e.g. \"+03:30\". Only ever set together "
                + "with `shotAt`. When absent, show the time without claiming a local time zone.", nullable = true,
                example = "+03:30")
        @Nullable String shotAtOffset,

        @Schema(description = "Where the shot was taken.", nullable = true)
        @Nullable GeoPointResponse location,

        @Schema(description = "Approximate altitude in metres above sea level.", nullable = true, example = "12.5")
        @Nullable BigDecimal altitudeM,

        @Schema(description = "Compass direction the camera faced, degrees clockwise from north, 0 to under 360. For "
                + "a 360° panorama, the direction its horizontal centre faces.", nullable = true, example = "135")
        @Nullable BigDecimal headingDeg,

        @Schema(description = "Which north `headingDeg` is measured from. May be absent even when `headingDeg` is set.",
                nullable = true)
        @Nullable HeadingReference headingRef) {
}
