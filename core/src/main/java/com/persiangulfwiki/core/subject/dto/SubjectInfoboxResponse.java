package com.persiangulfwiki.core.subject.dto;

import com.persiangulfwiki.core.label.dto.LocalizedLabelResponse;
import com.persiangulfwiki.core.subject.entity.SubjectKind;

import io.swagger.v3.oas.annotations.media.Schema;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.UUID;

public record SubjectInfoboxResponse(
        UUID subjectId,
        SubjectKind kind,
        @Schema(description = "The subject's name in the reader's language, or the Farsi name marked as a fallback. "
                + "Null only when the subject has no name at all.", nullable = true)
        @Nullable LocalizedLabelResponse name,
        @Schema(description = "The infobox image. Null when no image was picked or the picked one is no longer public.", nullable = true)
        @Nullable SubjectMainImageResponse mainImage,
        List<InfoboxGroupResponse> groups,
        @Schema(description = "The published revision the infobox shows. Null when no revision of the subject has "
                + "been approved yet, in which case `groups` is empty.", nullable = true)
        @Nullable InfoboxRevisionResponse revision) {
}
