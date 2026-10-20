package com.persiangulfwiki.core.subject.exception;

import com.persiangulfwiki.core.common.exception.NotFoundException;

import java.io.Serial;

public class SubjectRevisionNotFoundException extends NotFoundException {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String DEFAULT_MESSAGE = "subject revision not found";

    public SubjectRevisionNotFoundException() {
        super(DEFAULT_MESSAGE);
    }

    public SubjectRevisionNotFoundException(String message) {
        super(message);
    }
}
