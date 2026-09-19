package com.persiangulfwiki.core.article.exception;

import com.persiangulfwiki.core.common.exception.ConflictException;

public class RevisionNotEditableException extends ConflictException {

    private static final String DEFAULT_MESSAGE = "revision is not editable in its current status";

    public RevisionNotEditableException() {
        super(DEFAULT_MESSAGE);
    }

    public RevisionNotEditableException(String message) {
        super(message);
    }
}
