package com.persiangulfwiki.core.media.entity;

// No CHANGES_REQUESTED, unlike RevisionStatus: media moderation is APPROVE/REJECT only.
// Proposing a new version is how a contributor "fixes" a rejected one.
public enum MetadataVersionStatus {
    PENDING_REVIEW,
    APPROVED,
    REJECTED
}
