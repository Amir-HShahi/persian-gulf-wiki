package com.persiangulfwiki.core.topic.dto;

import com.persiangulfwiki.core.label.dto.LabelValuesRequest;
import com.persiangulfwiki.core.topic.validation.ValidTopicColor;
import com.persiangulfwiki.core.topic.validation.ValidTopicIcon;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.jspecify.annotations.Nullable;

// `slug` is permanent: no endpoint renames it (see V30). An omitted `sortOrder` places the topic
// after every existing one.
public record CreateTopicRequest(
        @Schema(description = "Permanent code for the topic, e.g. `marine-life`: a lower-case letter, then lower-case "
                + "letters, digits or hyphens; at most 60 characters.")
        @NotBlank(message = "{validation.topicSlug.required}")
        @Size(max = 60, message = "{validation.slug.tooLong}")
        @Pattern(regexp = "[a-z][a-z0-9-]*", message = "{validation.topicSlug.format}") String slug,
        @Schema(description = "Lucide icon name in kebab case, e.g. `fish` or `map-pin`; at most 64 characters. Only "
                + "the form is checked, not that Lucide has such an icon.")
        @NotBlank(message = "{validation.topicIcon.required}") @ValidTopicIcon String icon,
        @Schema(description = "`#RRGGBB` hex color, e.g. `#1288B6`. Either case is accepted; it is stored upper-case.")
        @NotBlank(message = "{validation.topicColor.required}") @ValidTopicColor String color,
        @Schema(description = "Position on the home page, ascending. Omit to place the topic last.")
        @Nullable Integer sortOrder,
        @NotNull(message = "{validation.topicNames.required}") @Valid LabelValuesRequest names) {
}
