package com.persiangulfwiki.core.revisioncheck.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import org.jspecify.annotations.Nullable;

// Positions count in the revision's title, summary and body prose combined -- the title is line 1.
public record TextFindingResponse(
        @Schema(description = "Which rule fired, e.g. `arabic_yeh`, `detached_prefix`, `zero_width_space`. An open list: "
                + "new rules can appear at any time, so label the ones you know and show the others by `found` and "
                + "`suggest` alone.", example = "detached_prefix")
        String rule,

        @Schema(description = "1-based line in the combined text (title, then summary, then body prose).",
                nullable = true, example = "2")
        @Nullable Integer line,

        @Schema(description = "1-based column within that line, counted in Unicode code points (not UTF-16 "
                + "units).", nullable = true, example = "4")
        @Nullable Integer column,

        @Schema(description = "0-based offset in the combined text, counted in UTF-8 bytes.", nullable = true,
                example = "21")
        @Nullable Integer offset,

        @Schema(description = "The text as written. Plain text -- escape it. May be or contain an invisible "
                + "character, which is often the point of the finding.", nullable = true, example = "می شود")
        @Nullable String found,

        @Schema(description = "What to write instead. Plain text -- escape it. Empty means \"remove `found`\".",
                nullable = true, example = "می‌شود")
        @Nullable String suggest,

        @Schema(description = "The words around the finding, for showing it in place. Plain text -- escape it.",
                nullable = true)
        @Nullable String context) {
}
