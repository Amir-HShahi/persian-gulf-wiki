package com.persiangulfwiki.core.article.entity;

import io.swagger.v3.oas.annotations.media.Schema;

// DRAFT/PENDING/APPROVED/REJECTED come from the ERD as given. CHANGES_REQUESTED does not --
// it was added by an earlier resolved design decision, not a Phase 2 invention: Phase 3's
// moderator REQUEST_CHANGES decision has nowhere else to land a revision that is distinct
// from both DRAFT (never submitted) and PENDING (still awaiting a first review). Without it,
// a moderator asking for changes would have to either reset the revision to DRAFT (losing the
// "this was reviewed once and sent back" fact) or leave it PENDING (which reads as still
// awaiting review, not as needing author action). ArticleRevisionService.update accepts an
// edit while status is DRAFT or CHANGES_REQUESTED; every other status is
// RevisionNotEditableException.
@Schema(description = RevisionStatus.VALUES_DOC)
public enum RevisionStatus {
    DRAFT, PENDING, CHANGES_REQUESTED, APPROVED, REJECTED;

    // One line per value; shown wherever this type appears in the API docs.
    public static final String VALUES_DOC = """
            - `DRAFT`: being written and not yet submitted; its author can still edit it.
            - `PENDING`: submitted and waiting for a moderator.
            - `CHANGES_REQUESTED`: a moderator sent it back; its author edits it and submits it again.
            - `APPROVED`: accepted and published; readers see it until a newer revision is approved.
            - `REJECTED`: refused. Final: it can't be edited or resubmitted, so trying again means writing a new \
            revision.""";
}
