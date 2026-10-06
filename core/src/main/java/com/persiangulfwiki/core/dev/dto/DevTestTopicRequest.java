package com.persiangulfwiki.core.dev.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.jspecify.annotations.Nullable;

// Every field optional. `slug` defaults to a generated e2e-... slug, `icon` to "pin", `sortOrder` to
// 1000 (after the seeded topics). Without `faLabel` the topic has no name at all — a state no seeded
// topic is in.
public record DevTestTopicRequest(
        @Size(max = 60, message = "{validation.slug.tooLong}")
        @Pattern(regexp = "[a-z][a-z0-9-]*", message = "{validation.topicSlug.format}") @Nullable String slug,
        @Size(max = 40, message = "{validation.topicIcon.tooLong}") @Nullable String icon,
        @Nullable Integer sortOrder,
        @Size(max = 200, message = "{validation.label.tooLong}") @Nullable String faLabel,
        @Size(max = 200, message = "{validation.label.tooLong}") @Nullable String enLabel,
        @Size(max = 200, message = "{validation.label.tooLong}") @Nullable String arLabel) {
}
