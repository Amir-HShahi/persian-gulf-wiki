package com.persiangulfwiki.core.subject.exception;

import com.persiangulfwiki.core.common.exception.NotFoundException;

import java.io.Serial;

public class SubjectNotFoundException extends NotFoundException {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String DEFAULT_MESSAGE = "subject not found";

    public SubjectNotFoundException() {
        super(DEFAULT_MESSAGE);
    }

    public SubjectNotFoundException(String message) {
        super(message);
    }
}
