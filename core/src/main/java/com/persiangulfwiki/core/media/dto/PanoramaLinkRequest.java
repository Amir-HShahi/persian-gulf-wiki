package com.persiangulfwiki.core.media.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

// One hotspot as proposed. The rules that need a lookup -- both ends PANORAMA_360, no self-link,
// the target exists and the caller may see it -- are MediaMetadataVersionService's.
//
// @Digits caps both angles at two decimals because the columns are NUMERIC(5, 2): Postgres would
// otherwise round 359.999 up to 360.00, which ck_panorama_links_yaw then refuses as a 500.
public record PanoramaLinkRequest(
        @NotNull(message = "{validation.panoramaLinkTarget.required}")
        @Schema(description = "The gallery item the hotspot leads to. Must be a 360° panorama the caller can see "
                + "(approved, on an article they can read, or one they uploaded themselves); it may belong to "
                + "another article.")
        UUID toMediaId,

        @NotNull(message = "{validation.yaw.required}")
        @DecimalMin(value = "0", message = "{validation.yaw.range}")
        @DecimalMax(value = "360", inclusive = false, message = "{validation.yaw.range}")
        @Digits(integer = 3, fraction = 2, message = "{validation.angle.precision}")
        @Schema(description = "Where the hotspot sits horizontally: degrees clockwise from the panorama's own "
                + "horizontal centre, 0 (inclusive) to 360 (exclusive), at most two decimals.", example = "90")
        BigDecimal yawDeg,

        @NotNull(message = "{validation.pitch.required}")
        @DecimalMin(value = "-90", message = "{validation.pitch.range}")
        @DecimalMax(value = "90", message = "{validation.pitch.range}")
        @Digits(integer = 3, fraction = 2, message = "{validation.angle.precision}")
        @Schema(description = "Where the hotspot sits vertically: degrees above (positive) or below (negative) the "
                + "horizon, -90 to 90, at most two decimals.", example = "-5")
        BigDecimal pitchDeg,

        @Size(max = 200, message = "{validation.panoramaLinkLabel.tooLong}")
        @Schema(description = "Optional caption for the hotspot, at most 200 characters.", example = "Towards the harbour")
        String label) {
}
