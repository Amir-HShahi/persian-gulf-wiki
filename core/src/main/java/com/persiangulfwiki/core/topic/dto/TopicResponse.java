package com.persiangulfwiki.core.topic.dto;

import com.persiangulfwiki.core.label.dto.LocalizedLabelResponse;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

public record TopicResponse(
        UUID id,
        @Schema(description = "Stable code for the topic, e.g. `marine-life`. Never changes.")
        String slug,
        @Schema(description = "Lucide icon name, e.g. `fish` or `map-pin`. Not checked against Lucide, so show a "
                + "fallback icon for a name the client doesn't know.")
        String icon,
        @Schema(description = "Upper-case `#RRGGBB` hex color, e.g. `#1288B6`.")
        String color,
        @Schema(description = "The topic's name in the requested language, or the Farsi one marked as a fallback. "
                + "Null only if the topic has no Farsi name either.")
        LocalizedLabelResponse name,
        @Schema(description = "Articles about this topic that are published in the requested language.")
        long articleCount) {
}
