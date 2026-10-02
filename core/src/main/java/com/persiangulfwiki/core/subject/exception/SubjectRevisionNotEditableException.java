package com.persiangulfwiki.core.subject.exception;

import com.persiangulfwiki.core.common.exception.ConflictException;

public class SubjectRevisionNotEditableException extends ConflictException {

    private static final String DEFAULT_MESSAGE = "subject revision not editable in its current status";

    public SubjectRevisionNotEditableException() {
        super(DEFAULT_MESSAGE);
    }

    public SubjectRevisionNotEditableException(String message) {
        super(message);
    }
}
