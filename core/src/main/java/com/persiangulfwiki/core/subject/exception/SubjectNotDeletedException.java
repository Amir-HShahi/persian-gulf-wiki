package com.persiangulfwiki.core.subject.exception;

import com.persiangulfwiki.core.common.exception.ConflictException;

import java.io.Serial;

public class SubjectNotDeletedException extends ConflictException {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String DEFAULT_MESSAGE = "subject is not deleted";

    public SubjectNotDeletedException() {
        super(DEFAULT_MESSAGE);
    }

    public SubjectNotDeletedException(String message) {
        super(message);
    }
}
