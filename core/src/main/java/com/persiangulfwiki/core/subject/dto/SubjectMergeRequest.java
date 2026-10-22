package com.persiangulfwiki.core.subject.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record SubjectMergeRequest(
        @Schema(description = "The subject to merge into. It keeps its id; the subject in the path is merged away.")
        @NotNull(message = "{validation.subjectMerge.targetRequired}") UUID targetId) {
}
