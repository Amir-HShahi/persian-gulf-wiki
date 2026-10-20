package com.persiangulfwiki.core.subject.dto;

import com.persiangulfwiki.core.subject.entity.SubjectKind;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.jspecify.annotations.Nullable;

// Only the envelope is checked here. The payload is checked field by field against the kind's
// vocabulary in SubjectPayloadValidationService, which the submit and approve paths run again.
// `kind` is bound as the enum: an unknown value fails binding as a 400, as on the old create route.
public record CreateSubjectProposalRequest(
        @NotNull(message = "{validation.subjectKind.required}") SubjectKind kind,
        @NotNull(message = "{validation.subjectPayload.required}") SubjectPayloadRequest payload,
        @Size(max = 2000, message = "{validation.subjectRevisionSummary.tooLong}") @Nullable String summary) {
}
