package com.persiangulfwiki.core.revisioncheck.dto;

import com.persiangulfwiki.core.revisioncheck.entity.RevisionCheckStatus;

import io.swagger.v3.oas.annotations.media.Schema;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;

public record TextCheckResponse(
        @Schema(description = RevisionCheckStatus.VALUES_DOC)
        RevisionCheckStatus status,

        @Schema(description = "Set only when `status` is REJECTED: why the check refused to run, e.g. "
                + "`unsupported_locale` (no rules for the translation's language) or `revision_missing`. An open "
                + "list.", nullable = true, example = "unsupported_locale")
        @Nullable String reasonCode,

        @Schema(description = "Set only when `status` is DONE: every finding, including any left out of "
                + "`findings`.", nullable = true, example = "2")
        @Nullable Integer findingCount,

        @Schema(description = "Set only when `status` is DONE: how many findings were left out of `findings` "
                + "because there were too many to list.", nullable = true, example = "0")
        @Nullable Integer omittedCount,

        @Schema(description = "The suggestions, in text order. Empty unless `status` is DONE. Each is a suggestion "
                + "for a reviewer to accept or ignore -- a spelling can be a deliberate quotation; nothing is ever "
                + "changed automatically.")
        List<TextFindingResponse> findings,

        @Schema(description = "When the check finished. Null while PENDING.", nullable = true)
        @Nullable Instant checkedAt) {
}
