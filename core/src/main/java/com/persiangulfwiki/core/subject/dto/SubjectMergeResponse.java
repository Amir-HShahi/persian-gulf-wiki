package com.persiangulfwiki.core.subject.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

public record SubjectMergeResponse(
        @Schema(description = "The merged-away subject. Reading it now answers with the target.")
        UUID sourceId,
        UUID targetId,
        Instant mergedAt,
        @Schema(description = "The target's new published revision, holding what both subjects said.")
        UUID revisionId,
        int revisionNumber) {
}
