package com.persiangulfwiki.core.dev.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

// Both must be subjects the dev fixture route minted, and the target must not be deleted. Nothing
// else of a merge happens: no revision, no facts or articles move. That is the point -- it reaches
// the merged-away state (redirect plus deleted source) without a moderator account or two subjects
// whose payloads would pass a real merge's validation.
public record DevTestSubjectRedirectRequest(
        @NotNull UUID sourceId,
        @NotNull UUID targetId) {
}
