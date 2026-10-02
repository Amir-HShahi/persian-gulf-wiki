package com.persiangulfwiki.core.subject.exception;

import com.persiangulfwiki.core.common.exception.NotFoundException;

public class SubjectRevisionNotFoundException extends NotFoundException {

    private static final String DEFAULT_MESSAGE = "subject revision not found";

    public SubjectRevisionNotFoundException() {
        super(DEFAULT_MESSAGE);
    }

    public SubjectRevisionNotFoundException(String message) {
        super(message);
    }
}
