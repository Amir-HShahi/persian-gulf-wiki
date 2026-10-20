package com.persiangulfwiki.core.topic.dto;

import com.persiangulfwiki.core.label.dto.LabelValuesResponse;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

public record AdminTopicResponse(
        UUID id,
        @Schema(description = "Permanent code for the topic, e.g. `marine-life`.")
        String slug,
        @Schema(description = "Lucide icon name, e.g. `fish`.")
        String icon,
        @Schema(description = "Upper-case `#RRGGBB` hex color, e.g. `#1288B6`.")
        String color,
        int sortOrder,
        @Schema(description = "Archived topics are hidden from the home page and can't be added to articles.")
        boolean isArchived,
        @Schema(description = "The topic's name in each language; null where it has none.")
        LabelValuesResponse names,
        Instant createdAt,
        Instant updatedAt) {
}
