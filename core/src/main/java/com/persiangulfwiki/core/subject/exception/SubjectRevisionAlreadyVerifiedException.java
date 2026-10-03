package com.persiangulfwiki.core.subject.exception;

import com.persiangulfwiki.core.common.exception.ConflictException;

import java.io.Serial;

public class SubjectRevisionAlreadyVerifiedException extends ConflictException {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String DEFAULT_MESSAGE = "subject revision already verified by this expert";

    public SubjectRevisionAlreadyVerifiedException() {
        super(DEFAULT_MESSAGE);
    }

    public SubjectRevisionAlreadyVerifiedException(String message) {
        super(message);
    }
}
