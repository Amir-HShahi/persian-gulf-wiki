package com.persiangulfwiki.core.moderation.dto;

import com.persiangulfwiki.core.media.dto.MediaReviewResponse;
import com.persiangulfwiki.core.revisioncheck.dto.RevisionChecksResponse;
import com.persiangulfwiki.core.subject.dto.SubjectReviewResponse;

import io.swagger.v3.oas.annotations.media.Schema;

import org.jspecify.annotations.Nullable;

// A task plus what it judges, for the one moderator screen that needs both. Kept out of
// ModerationTaskResponse so the queue list and claim/decide responses stay light: building
// mediaReview signs URLs for every rendition of the item, subjectReview diffs two payloads, and
// revisionChecks carries up to hundreds of findings.
public record ModerationTaskDetailResponse(
        ModerationTaskResponse task,

        @Schema(description = "Set only for a gallery item task: the item and the metadata version under review. "
                + "Null for any other task.", nullable = true)
        @Nullable MediaReviewResponse mediaReview,

        @Schema(description = "Set only for a subject revision task: the revision, a field-by-field comparison with "
                + "what readers see, and the sources the changed values cite. Null for any other task. An article "
                + "revision task has neither; read the revision through the article routes.", nullable = true)
        @Nullable SubjectReviewResponse subjectReview,

        @Schema(description = "Set only for an article revision task: the automatic text and dead-link checks of "
                + "the revision, as of its latest submit. Advisory -- they never block a decision. Null for any "
                + "other task.", nullable = true)
        @Nullable RevisionChecksResponse revisionChecks) {
}
