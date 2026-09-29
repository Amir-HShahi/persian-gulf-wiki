package com.persiangulfwiki.core.media.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.util.UUID;

public record PanoramaLinkResponse(
        UUID id,

        @Schema(description = "Where the hotspot sits horizontally: degrees clockwise from the panorama's own "
                + "horizontal centre, 0 to under 360.", example = "90")
        BigDecimal yawDeg,

        @Schema(description = "Where the hotspot sits vertically: degrees above (positive) or below (negative) the "
                + "horizon, -90 to 90.", example = "-5")
        BigDecimal pitchDeg,

        @Schema(description = "Optional caption for the hotspot.", nullable = true)
        @Nullable String label,

        PanoramaLinkTargetResponse target) {
}
