package com.persiangulfwiki.core.subject.exception;

import com.persiangulfwiki.core.common.exception.NotFoundException;

public class SubjectNotFoundException extends NotFoundException {

    private static final String DEFAULT_MESSAGE = "subject not found";

    public SubjectNotFoundException() {
        super(DEFAULT_MESSAGE);
    }

    public SubjectNotFoundException(String message) {
        super(message);
    }
}
