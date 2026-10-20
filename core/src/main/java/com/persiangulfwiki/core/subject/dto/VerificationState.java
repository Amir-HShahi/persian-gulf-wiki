package com.persiangulfwiki.core.subject.dto;

// Computed against the subject's live revision, never stored: VERIFIED when it carries an expert's
// mark, STALE when only older revisions do, NONE when no revision does.
public enum VerificationState {
    VERIFIED, STALE, NONE
}
