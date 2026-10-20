package com.persiangulfwiki.core.dev.dto;

import com.persiangulfwiki.core.subject.entity.SubjectKind;
import com.persiangulfwiki.core.subject.entity.SubjectRevisionStatus;

import java.util.UUID;

// subjectId is null for a proposal that was not minted APPROVED.
public record DevTestSubjectRevisionResponse(
        UUID revisionId,
        UUID subjectId,
        SubjectKind kind,
        int revisionNumber,
        UUID baseRevisionId,
        SubjectRevisionStatus status,
        boolean isLive) {
}
