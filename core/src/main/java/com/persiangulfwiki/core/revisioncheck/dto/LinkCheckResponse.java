package com.persiangulfwiki.core.revisioncheck.dto;

import com.persiangulfwiki.core.revisioncheck.entity.RevisionCheckStatus;

import io.swagger.v3.oas.annotations.media.Schema;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;

public record LinkCheckResponse(
        @Schema(description = RevisionCheckStatus.VALUES_DOC)
        RevisionCheckStatus status,

        @Schema(description = "Set only when `status` is REJECTED: why the check refused to run, e.g. "
                + "`revision_missing` or `malformed_body`. An open list.", nullable = true)
        @Nullable String reasonCode,

        @Schema(description = "Set only when `status` is DONE: how many external links were checked.",
                nullable = true, example = "3")
        @Nullable Integer checkedCount,

        @Schema(description = "Set only when `status` is DONE: how many of them are broken or unreachable -- the "
                + "number worth a moderator's look. Links that only refuse robots (`restricted`) are not counted.",
                nullable = true, example = "1")
        @Nullable Integer brokenCount,

        @Schema(description = "Set only when `status` is DONE: external links past the per-revision limit, which "
                + "were never checked.", nullable = true, example = "0")
        @Nullable Integer uncheckedCount,

        @Schema(description = "One entry per checked link, in body order. Empty unless `status` is DONE.")
        List<LinkResultResponse> links,

        @Schema(description = "When the check finished. Null while PENDING.", nullable = true)
        @Nullable Instant checkedAt) {
}
