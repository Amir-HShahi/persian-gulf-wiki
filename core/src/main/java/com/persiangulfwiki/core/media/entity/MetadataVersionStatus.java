package com.persiangulfwiki.core.media.entity;

import io.swagger.v3.oas.annotations.media.Schema;

// No CHANGES_REQUESTED, unlike RevisionStatus: media moderation is APPROVE/REJECT only.
// Proposing a new version is how a contributor "fixes" a rejected one.
@Schema(description = MetadataVersionStatus.VALUES_DOC)
public enum MetadataVersionStatus {
    PENDING_REVIEW,
    APPROVED,
    REJECTED;

    // One line per value; shown wherever this type appears in the API docs.
    public static final String VALUES_DOC = """
            - `PENDING_REVIEW`: waiting for a moderator.
            - `APPROVED`: accepted.
            - `REJECTED`: refused; propose a new version to fix it.""";
}
