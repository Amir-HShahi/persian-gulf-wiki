package com.persiangulfwiki.core.subject.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import org.jspecify.annotations.Nullable;

public record SubjectVerificationResponse(
        @Schema(description = VerificationState.VALUES_DOC)
        VerificationState state,
        @Schema(description = "The newest revision an expert confirmed: the current one when VERIFIED, an older one "
                + "when STALE, null when NONE.", nullable = true)
        @Nullable Integer verifiedRevisionNumber) {
}
