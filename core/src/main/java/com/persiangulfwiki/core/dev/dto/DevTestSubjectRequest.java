package com.persiangulfwiki.core.dev.dto;

import com.persiangulfwiki.core.subject.entity.SubjectKind;

// Every field optional, so the common case is a bare `{}` (or no body at all): an island with
// no detail row. `kind` defaults to ISLAND.
//
// withDetailRow is the point of this endpoint. The real create path always writes the subtype
// row alongside the subject, so a subject with no detail row is unreachable through the API —
// yet it is exactly the state a test needs to prove that reading such a subject returns null
// detail fields rather than failing. Set it false to mint one.
//
// deleted mints the subject already soft-deleted (hidden from every public read, restorable),
// without a moderator account. For the merged-away state, mint two subjects and link them through
// POST /api/dev/test-subject-redirects.
public record DevTestSubjectRequest(SubjectKind kind, Boolean withDetailRow, Boolean deleted) {
}
