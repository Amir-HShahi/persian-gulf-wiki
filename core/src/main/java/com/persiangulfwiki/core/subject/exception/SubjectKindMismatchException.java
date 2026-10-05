package com.persiangulfwiki.core.subject.exception;

import com.persiangulfwiki.core.common.exception.ConflictException;

import java.io.Serial;

public class SubjectKindMismatchException extends ConflictException {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String DEFAULT_MESSAGE = "subjects of different kinds cannot be merged";

    public SubjectKindMismatchException() {
        super(DEFAULT_MESSAGE);
    }

    public SubjectKindMismatchException(String message) {
        super(message);
    }
}
