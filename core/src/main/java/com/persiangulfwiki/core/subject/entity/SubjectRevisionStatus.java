package com.persiangulfwiki.core.subject.entity;

// The same lifecycle as an article revision (see the article package's RevisionStatus), kept as
// an enum of its own because the article package already depends on this one, and sharing
// theirs would make the dependency run both ways. Mirrored by V24's ck_subject_revisions_status.
public enum SubjectRevisionStatus {
    DRAFT, PENDING, CHANGES_REQUESTED, APPROVED, REJECTED
}
