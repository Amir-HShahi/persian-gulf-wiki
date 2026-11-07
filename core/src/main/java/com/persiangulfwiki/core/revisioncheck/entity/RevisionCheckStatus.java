package com.persiangulfwiki.core.revisioncheck.entity;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = RevisionCheckStatus.VALUES_DOC)
public enum RevisionCheckStatus {
    PENDING,
    DONE,
    FAILED,
    REJECTED;

    // One line per value; shown wherever this type appears in the API docs.
    public static final String VALUES_DOC = """
            - `PENDING`: queued or running. A link check can take a minute or more.
            - `DONE`: finished; the findings are ready.
            - `FAILED`: the check broke down and gave up. Nothing is known about the revision.
            - `REJECTED`: the check refused to run, e.g. no rules exist for the translation's language; see \
            `reasonCode`.""";
}
