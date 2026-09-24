package com.persiangulfwiki.core.moderation.dto;

import com.persiangulfwiki.core.moderation.entity.Decision;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// Bound as the enum type directly, unlike the ?state= query filter on the list endpoint.
// That asymmetry is deliberate: a body field's bad value already produces a translated,
// field-named 400 through the standard validation path, whereas a query parameter bound to
// an enum produces a generic conversion failure with no field context -- which is why the
// filter takes a raw String and is parsed by hand instead.
public record DecideRequest(
        @NotNull
        @Schema(description = "APPROVE publishes the revision. REJECT ends it permanently -- the author must write a "
                + "new revision to try again. REQUEST_CHANGES sends it back to the author to fix and "
                + "resubmit, and keeps this same task alive for another round.")
        Decision decision,

        // Not @NotBlank: an approval genuinely has nothing to explain. Requiredness depends
        // on which decision was chosen, which a field-level annotation cannot express, so
        // ModerationService enforces it -- see requireReasonWhenSendingBack there.
        @Size(max = 2000)
        @Schema(description = "The moderator's note to the author. Required for REJECT and REQUEST_CHANGES, since the "
                + "author needs to know what was wrong; optional for APPROVE.")
        String reason) {
}
