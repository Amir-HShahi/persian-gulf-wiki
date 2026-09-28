package com.persiangulfwiki.core.moderation.exception;

import com.persiangulfwiki.core.common.exception.ConflictException;

// The media counterpart of RevisionNotPendingException.
public class MetadataVersionNotPendingException extends ConflictException {

    private static final String DEFAULT_MESSAGE = "metadata version is not awaiting review";

    public MetadataVersionNotPendingException() {
        super(DEFAULT_MESSAGE);
    }

    public MetadataVersionNotPendingException(String message) {
        super(message);
    }
}
