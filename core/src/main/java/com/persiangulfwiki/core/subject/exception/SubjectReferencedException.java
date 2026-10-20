package com.persiangulfwiki.core.subject.exception;

import com.persiangulfwiki.core.common.exception.ConflictException;

import java.io.Serial;

public class SubjectReferencedException extends ConflictException {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String DEFAULT_MESSAGE = "other subjects' facts refer to the subject";

    public SubjectReferencedException() {
        super(DEFAULT_MESSAGE);
    }

    public SubjectReferencedException(String message) {
        super(message);
    }
}
