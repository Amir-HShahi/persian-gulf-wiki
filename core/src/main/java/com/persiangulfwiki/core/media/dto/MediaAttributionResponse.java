package com.persiangulfwiki.core.media.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import org.jspecify.annotations.Nullable;

// What an item's original file claims about who made it and on what terms. Moderator-facing
// only, and only ever a hint -- see MediaAttribution.
@Schema(description = "What the uploaded file's own metadata (XMP, else EXIF) claims about who made it and on what "
        + "terms, read before that metadata was stripped from the renditions. A claim anyone can type into a file, "
        + "not proof and not the item's licence: compare it with what the uploader declared, and look twice at a "
        + "file that names someone else. Every field is plain text (escape it) and may be null.")
public record MediaAttributionResponse(
        @Schema(description = "The creator(s) the file names; several are joined with \"; \".", nullable = true,
                example = "مریم احمدی")
        @Nullable String author,

        @Schema(description = "The credit line the file carries.", nullable = true)
        @Nullable String credit,

        @Schema(description = "The copyright notice the file carries.", nullable = true, example = "© 2026 مریم احمدی")
        @Nullable String copyright,

        @Schema(description = "The usage terms the file states, e.g. a licence name.", nullable = true,
                example = "CC BY-SA 4.0")
        @Nullable String license,

        @Schema(description = "The licence's web address the file states. Always http or https.", nullable = true,
                example = "https://creativecommons.org/licenses/by-sa/4.0/")
        @Nullable String licenseUrl,

        @Schema(description = "When the file says it was created, as written in it: local time with no time zone, "
                + "e.g. 2024-05-01T10:20:30.", nullable = true, example = "2024-05-01T10:20:30")
        @Nullable String createdAt) {
}
