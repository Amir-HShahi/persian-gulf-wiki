package com.persiangulfwiki.core.topic.dto;

import com.persiangulfwiki.core.topic.validation.ValidTopicColor;
import com.persiangulfwiki.core.topic.validation.ValidTopicIcon;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

// Replaces every mutable field in place, not a merge-patch — same convention as
// UpdateAttributeGroupRequest — so every field is required. Boxed so an omitted one is a
// per-field 400 rather than an unreadable body. `slug` is permanent and names change through
// PUT /api/admin/labels.
public record UpdateTopicRequest(
        @Schema(description = "Lucide icon name in kebab case, e.g. `fish` or `map-pin`; at most 64 characters.")
        @NotBlank(message = "{validation.topicIcon.required}") @ValidTopicIcon String icon,
        @Schema(description = "`#RRGGBB` hex color, e.g. `#1288B6`. Either case is accepted; it is stored upper-case.")
        @NotBlank(message = "{validation.topicColor.required}") @ValidTopicColor String color,
        @Schema(description = "Position on the home page, ascending.")
        @NotNull(message = "{validation.topicSortOrder.required}") Integer sortOrder,
        @Schema(description = "True hides the topic from the home page and stops it being added to articles; articles "
                + "that already have it keep it. False restores it.")
        @NotNull(message = "{validation.topicArchived.required}") Boolean isArchived) {
}
