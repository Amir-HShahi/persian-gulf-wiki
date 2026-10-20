package com.persiangulfwiki.core.dev.dto;

import com.persiangulfwiki.core.topic.validation.ValidTopicColor;
import com.persiangulfwiki.core.topic.validation.ValidTopicIcon;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.jspecify.annotations.Nullable;

// Every field optional. `slug` defaults to a generated e2e-... slug, `icon` to "map-pin", `color` to
// #1288B6, `sortOrder` to 1000 (after any hand-made topic) and `isArchived` to false. Without
// `faLabel` the topic has no name at all — a state the admin API can't produce.
public record DevTestTopicRequest(
        @Size(max = 60, message = "{validation.slug.tooLong}")
        @Pattern(regexp = "[a-z][a-z0-9-]*", message = "{validation.topicSlug.format}") @Nullable String slug,
        @ValidTopicIcon @Nullable String icon,
        @ValidTopicColor @Nullable String color,
        @Nullable Integer sortOrder,
        @Nullable Boolean isArchived,
        @Size(max = 200, message = "{validation.label.tooLong}") @Nullable String faLabel,
        @Size(max = 200, message = "{validation.label.tooLong}") @Nullable String enLabel,
        @Size(max = 200, message = "{validation.label.tooLong}") @Nullable String arLabel) {
}
