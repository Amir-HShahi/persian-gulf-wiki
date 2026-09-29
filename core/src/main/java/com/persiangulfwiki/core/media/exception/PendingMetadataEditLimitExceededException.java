package com.persiangulfwiki.core.media.exception;

import com.persiangulfwiki.core.common.exception.ConflictException;

// The per-account cap on metadata edits awaiting review (app.media.max-pending-edits-per-user).
public class PendingMetadataEditLimitExceededException extends ConflictException {

    private static final String DEFAULT_MESSAGE = "too many metadata edits awaiting review";

    public PendingMetadataEditLimitExceededException() {
        super(DEFAULT_MESSAGE);
    }

    public PendingMetadataEditLimitExceededException(String message) {
        super(message);
    }
}
