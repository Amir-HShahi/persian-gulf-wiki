package com.persiangulfwiki.core.dev.dto;

import com.persiangulfwiki.core.revisioncheck.entity.RevisionCheckStatus;
import com.persiangulfwiki.core.revisioncheck.entity.RevisionCheckType;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

// revisionId is required -- any article revision, e.g. one from POST /api/dev/test-articles. The
// findings of a DONE check are a fixed sample (DevTestRevisionCheckController), chosen to cover
// what a renderer has to tell apart: for links, a working, a moved, a broken (with an archived
// copy) and a robots-refusing one, the last of which must not show as broken.
public record DevTestRevisionCheckRequest(
        @Schema(description = "The article revision to attach the check to. Required.")
        UUID revisionId,

        @Schema(description = "Which check. Default NORMALIZE.\n\n" + RevisionCheckType.VALUES_DOC)
        RevisionCheckType type,

        @Schema(description = "Which state to mint it in. Default DONE.\n\n" + RevisionCheckStatus.VALUES_DOC)
        RevisionCheckStatus status) {
}
