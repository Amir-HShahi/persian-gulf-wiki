package com.persiangulfwiki.core.subject.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.jspecify.annotations.Nullable;

// Replaces the payload and summary in place, not a merge-patch: omitting `summary` clears it,
// as on an article revision.
public record UpdateSubjectRevisionRequest(
        @NotNull(message = "{validation.subjectPayload.required}") SubjectPayloadRequest payload,
        @Size(max = 2000, message = "{validation.subjectRevisionSummary.tooLong}") @Nullable String summary) {
}
