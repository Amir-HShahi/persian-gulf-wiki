package com.persiangulfwiki.core.media.entity;

import org.jspecify.annotations.Nullable;

// The author and licence an item's original file states in its own metadata (XMP, else EXIF),
// as the pipeline read it before stripping that metadata from the variants. Stored in
// article_media.attribution (jsonb, V38).
//
// It is what the file claims, not a fact: anyone can type any name into a file. It is kept so a
// moderator can set it beside what the uploader declared and notice a photo whose file names
// someone else. Every field is optional, single-line text already cleaned by
// MediaProcessingService; licenseUrl is only ever an http(s) URL.
public record MediaAttribution(
        @Nullable String author,
        @Nullable String credit,
        @Nullable String copyright,
        @Nullable String license,
        @Nullable String licenseUrl,
        // As the file wrote it, e.g. 2024-05-01T10:20:30, with no time zone.
        @Nullable String createdAt) {
}
