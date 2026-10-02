package com.persiangulfwiki.core.subject.exception;

import com.persiangulfwiki.core.common.exception.ForbiddenException;

public class NotSubjectRevisionAuthorException extends ForbiddenException {

    private static final String DEFAULT_MESSAGE = "caller is not the subject revision's author";

    public NotSubjectRevisionAuthorException() {
        super(DEFAULT_MESSAGE);
    }

    public NotSubjectRevisionAuthorException(String message) {
        super(message);
    }
}
