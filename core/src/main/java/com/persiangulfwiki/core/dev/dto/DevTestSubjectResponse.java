package com.persiangulfwiki.core.dev.dto;

import com.persiangulfwiki.core.subject.entity.SubjectKind;

import java.util.UUID;

public record DevTestSubjectResponse(UUID subjectId, SubjectKind kind, boolean hasDetailRow) {
}
