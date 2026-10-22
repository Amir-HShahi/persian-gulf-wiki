package com.persiangulfwiki.core.moderation.exception;

import com.persiangulfwiki.core.common.exception.ConflictException;

import java.io.Serial;

public class RevisionNotPendingException extends ConflictException {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String DEFAULT_MESSAGE = "revision is not awaiting review";

    public RevisionNotPendingException() {
        super(DEFAULT_MESSAGE);
    }

    public RevisionNotPendingException(String message) {
        super(message);
    }
}
