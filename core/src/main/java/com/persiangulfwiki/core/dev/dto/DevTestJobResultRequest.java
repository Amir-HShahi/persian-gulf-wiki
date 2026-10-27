package com.persiangulfwiki.core.dev.dto;

import io.swagger.v3.oas.annotations.media.Schema;

// Every field optional: no body at all is an OK result without attribution. The result written is
// exactly what the worker would write, so the real poll and validation apply it -- a reasonCode
// core does not know ends as the generic `rejected`, as it would from the worker.
public record DevTestJobResultRequest(
        @Schema(description = "How the job finished. Default OK.\n\n" + DevTestJobOutcome.VALUES_DOC)
        DevTestJobOutcome outcome,

        @Schema(description = "REJECTED only: the worker's refusal code, e.g. `corrupt_file`, "
                + "`panorama_aspect_ratio`, `object_missing`. Default `corrupt_file`.", example = "corrupt_file")
        String reasonCode,

        @Schema(description = "OK only: whether the file claims an author and licence in its metadata, which then "
                + "shows in the moderator's review. Default false.")
        Boolean withAttribution) {
}
