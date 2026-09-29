package com.persiangulfwiki.core.media.dto;

import com.persiangulfwiki.core.media.entity.HeadingReference;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

// Every field is optional, and that is the point: a contributor may skip any of them, and an
// unreliable value (phone GPS here is routinely spoofed or jammed) is better left out than
// guessed. The two cross-field rules -- shotAtOffset needs shotAt, headingRef needs headingDeg
// -- are enforced by MediaMetadataVersionService (IncompleteMediaMetadataException), since a
// field-level annotation cannot read a sibling; so are the panorama-link rules that need a lookup.
//
// The same shape serves the metadata sent with an upload (version 1) and every later edit, and
// either way it is the *whole* version: nothing omitted here is carried over from an earlier one.
public record MediaMetadataRequest(
        @PastOrPresent(message = "{validation.shotAt.notFuture}")
        @Schema(description = "When the shot was taken, as an instant (UTC).", example = "2026-04-12T06:30:00Z")
        Instant shotAt,

        @Pattern(regexp = "^[+-](0[0-9]|1[0-4]):[0-5][0-9]$", message = "{validation.shotAtOffset.format}")
        @Schema(description = "UTC offset in force where the shot was taken, e.g. \"+03:30\". Requires `shotAt`. "
                + "Omit it when unknown -- it is never inferred.", example = "+03:30")
        String shotAtOffset,

        @Valid
        @Schema(description = "Where the shot was taken. Omit when unknown.")
        GeoPointRequest location,

        @DecimalMin(value = "-500", message = "{validation.altitude.range}")
        @DecimalMax(value = "9000", message = "{validation.altitude.range}")
        @Schema(description = "Approximate altitude in metres above sea level, -500 to 9000.", example = "12.5")
        BigDecimal altitudeM,

        @DecimalMin(value = "0", message = "{validation.heading.range}")
        @DecimalMax(value = "360", inclusive = false, message = "{validation.heading.range}")
        // NUMERIC(5, 2): a third decimal would be rounded by Postgres, and 359.999 would round to
        // 360.00 and fail ck_media_metadata_versions_heading_range as a 500. See PanoramaLinkRequest.
        @Digits(integer = 3, fraction = 2, message = "{validation.angle.precision}")
        @Schema(description = "Compass direction the camera faced, in degrees clockwise from north, 0 (inclusive) "
                + "to 360 (exclusive), at most two decimals.", example = "135")
        BigDecimal headingDeg,

        @Schema(description = "Which north `headingDeg` is measured from. Requires `headingDeg`; omit when unknown.")
        HeadingReference headingRef,

        @Size(max = 10, message = "{validation.mediaDescriptions.tooMany}")
        @Schema(description = "Caption per language, keyed by BCP-47 tag (the same codes as article translations), "
                + "e.g. {\"fa\": \"...\", \"en\": \"...\"}. At most 10 languages.")
        Map<@NotBlank(message = "{validation.language.required}") @Size(max = 20, message = "{validation.language.tooLong}") String,
                @NotBlank(message = "{validation.mediaDescription.required}") @Size(max = 2000, message = "{validation.mediaDescription.tooLong}") String> descriptions,

        @Valid
        @Size(max = 20, message = "{validation.panoramaLinks.tooMany}")
        @Schema(description = "Hotspots leading to other 360° panoramas. Only a 360° panorama may have them; at most "
                + "20. One-way: a hotspot from A to B adds nothing to B.")
        List<@NotNull(message = "{validation.panoramaLink.required}") PanoramaLinkRequest> links) {
}
