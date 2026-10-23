package com.persiangulfwiki.core.subject.dto;

import io.swagger.v3.oas.annotations.media.Schema;

// Computed against the subject's live revision, never stored: VERIFIED when it carries an expert's
// mark, STALE when only older revisions do, NONE when no revision does.
@Schema(description = VerificationState.VALUES_DOC)
public enum VerificationState {
    VERIFIED, STALE, NONE;

    // One line per value; shown wherever this type appears in the API docs.
    public static final String VALUES_DOC = """
            - `VERIFIED`: an expert has confirmed the revision readers see now.
            - `STALE`: an expert confirmed an older revision, but not the current one.
            - `NONE`: no revision has been confirmed by an expert.""";
}
