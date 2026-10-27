package com.persiangulfwiki.core.revisioncheck.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import org.jspecify.annotations.Nullable;

import java.util.UUID;

// Both advisory checks of one revision. Each is null until the revision has been submitted
// once since the checks existed; afterwards every submit resets both to PENDING.
@Schema(description = "The automatic checks of a submitted revision: suggested text fixes and dead links. Advisory "
        + "only -- they never block a moderation decision, and every submit (the first, and each resubmit after "
        + "changes were requested) runs both again, replacing the previous findings.")
public record RevisionChecksResponse(
        UUID revisionId,

        @Schema(description = "Suggested text fixes. Null when the revision has not been submitted since the "
                + "checks were introduced.", nullable = true)
        @Nullable TextCheckResponse textCheck,

        @Schema(description = "Dead-link check of the body's external links. Null when the revision has not been "
                + "submitted since the checks were introduced.", nullable = true)
        @Nullable LinkCheckResponse linkCheck) {
}
