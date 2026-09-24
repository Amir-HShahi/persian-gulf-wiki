package com.persiangulfwiki.core.moderation.exception;

import com.persiangulfwiki.core.common.exception.ConflictException;

public class RevisionNotPendingException extends ConflictException {

    private static final String DEFAULT_MESSAGE = "revision is not awaiting review";

    public RevisionNotPendingException() {
        super(DEFAULT_MESSAGE);
    }

    public RevisionNotPendingException(String message) {
        super(message);
    }
}
