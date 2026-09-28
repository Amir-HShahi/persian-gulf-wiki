package com.persiangulfwiki.core.moderation.dto;

import com.persiangulfwiki.core.media.dto.MediaReviewResponse;

import io.swagger.v3.oas.annotations.media.Schema;

import org.jspecify.annotations.Nullable;

// A task plus what it judges, for the one moderator screen that needs both. Kept out of
// ModerationTaskResponse so the queue list and claim/decide responses stay light: building
// mediaReview signs URLs for every rendition of the item.
public record ModerationTaskDetailResponse(
        ModerationTaskResponse task,

        @Schema(description = "Set only for a gallery item task: the item and the metadata version under review. "
                + "For an article revision task this is null; read the revision through the article routes.",
                nullable = true)
        @Nullable MediaReviewResponse mediaReview) {
}
