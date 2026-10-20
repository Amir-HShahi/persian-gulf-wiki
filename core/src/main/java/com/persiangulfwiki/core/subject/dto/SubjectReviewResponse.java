package com.persiangulfwiki.core.subject.dto;

import com.persiangulfwiki.core.source.dto.SourceResponse;

import io.swagger.v3.oas.annotations.media.Schema;

import org.jspecify.annotations.Nullable;

import java.util.List;

// What a moderator needs to judge a subject revision on policy: the revision, what it changes,
// and where each changed value claims to come from.
public record SubjectReviewResponse(
        SubjectRevisionResponse revision,
        @Schema(description = "The revision number readers currently see. Null when the subject has no approved "
                + "revision yet, including on a proposal.", nullable = true)
        @Nullable Integer liveRevisionNumber,
        @Schema(description = "Only the fields that differ. While the revision awaits a decision it is compared with "
                + "the live revision; once decided, with the revision it was based on. On a proposal every field "
                + "it sets is a change from nothing.")
        List<SubjectFieldChangeResponse> changes,
        @Schema(description = "Every source cited by a changed fact value.")
        List<SourceResponse> sources) {
}
