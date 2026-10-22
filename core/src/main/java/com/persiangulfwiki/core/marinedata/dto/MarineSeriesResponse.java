package com.persiangulfwiki.core.marinedata.dto;

import com.persiangulfwiki.core.marinedata.entity.MarineMetric;
import com.persiangulfwiki.core.marinedata.entity.MarineSeriesRange;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

public record MarineSeriesResponse(
        @Schema(description = MarineMetric.VALUES_DOC, example = "sea_surface_temperature")
        String metricKey,

        @Schema(example = "bushehr")
        String stationKey,

        @Schema(description = MarineSeriesRange.VALUES_DOC, example = "WEEK")
        String range,

        @Schema(description = "Stable unit key; display text is the client's job.", example = "celsius")
        String unit,

        @Schema(description = "Oldest first. A bucket with no readings is left out rather than sent as null, so DAY has "
                + "up to 24 points and WEEK/MONTH up to 7/30.")
        List<MarineSeriesPointResponse> points) {

    public MarineSeriesResponse {
        points = List.copyOf(points);
    }
}
