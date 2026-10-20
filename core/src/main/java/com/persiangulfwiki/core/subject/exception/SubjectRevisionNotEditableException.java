package com.persiangulfwiki.core.subject.exception;

import com.persiangulfwiki.core.common.exception.ConflictException;

import java.io.Serial;

public class SubjectRevisionNotEditableException extends ConflictException {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String DEFAULT_MESSAGE = "subject revision not editable in its current status";

    public SubjectRevisionNotEditableException() {
        super(DEFAULT_MESSAGE);
    }

    public SubjectRevisionNotEditableException(String message) {
        super(message);
    }
}
