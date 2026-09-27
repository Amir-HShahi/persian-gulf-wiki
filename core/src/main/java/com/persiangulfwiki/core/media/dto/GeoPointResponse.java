package com.persiangulfwiki.core.media.dto;

import io.swagger.v3.oas.annotations.media.Schema;

public record GeoPointResponse(
        @Schema(description = "WGS 84 latitude in decimal degrees.", example = "26.5667")
        double latitude,

        @Schema(description = "WGS 84 longitude in decimal degrees.", example = "56.25")
        double longitude) {
}
