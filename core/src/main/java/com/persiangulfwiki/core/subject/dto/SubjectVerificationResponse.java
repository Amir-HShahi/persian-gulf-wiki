package com.persiangulfwiki.core.subject.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import org.jspecify.annotations.Nullable;

public record SubjectVerificationResponse(
        @Schema(description = "VERIFIED: an expert has confirmed the revision readers see now. STALE: an expert "
                + "confirmed an older revision, but not the current one. NONE: no revision has been confirmed.")
        VerificationState state,
        @Schema(description = "The newest revision an expert confirmed: the current one when VERIFIED, an older one "
                + "when STALE, null when NONE.", nullable = true)
        @Nullable Integer verifiedRevisionNumber) {
}
