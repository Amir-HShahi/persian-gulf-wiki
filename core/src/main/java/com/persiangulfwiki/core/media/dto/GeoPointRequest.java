package com.persiangulfwiki.core.media.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

// Both halves required once a location is sent at all: a latitude with no longitude is not a
// place. Omitting the whole object is how a contributor says "location unknown".
public record GeoPointRequest(
        @NotNull(message = "{validation.latitude.required}")
        @DecimalMin(value = "-90", message = "{validation.latitude.range}")
        @DecimalMax(value = "90", message = "{validation.latitude.range}")
        @Schema(description = "WGS 84 latitude in decimal degrees, -90 to 90.", example = "26.5667")
        Double latitude,

        @NotNull(message = "{validation.longitude.required}")
        @DecimalMin(value = "-180", message = "{validation.longitude.range}")
        @DecimalMax(value = "180", message = "{validation.longitude.range}")
        @Schema(description = "WGS 84 longitude in decimal degrees, -180 to 180.", example = "56.25")
        Double longitude) {
}
