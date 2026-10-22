package com.persiangulfwiki.core.subject.entity;

import io.swagger.v3.oas.annotations.media.Schema;

// The same lifecycle as an article revision (see the article package's RevisionStatus), kept as
// an enum of its own because the article package already depends on this one, and sharing
// theirs would make the dependency run both ways. Mirrored by V24's ck_subject_revisions_status.
@Schema(description = SubjectRevisionStatus.VALUES_DOC)
public enum SubjectRevisionStatus {
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
