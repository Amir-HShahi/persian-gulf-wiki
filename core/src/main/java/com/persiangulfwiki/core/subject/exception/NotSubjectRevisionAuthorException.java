package com.persiangulfwiki.core.subject.exception;

import com.persiangulfwiki.core.common.exception.ForbiddenException;

import java.io.Serial;

public class NotSubjectRevisionAuthorException extends ForbiddenException {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String DEFAULT_MESSAGE = "caller is not the subject revision's author";

    public NotSubjectRevisionAuthorException() {
        super(DEFAULT_MESSAGE);
    }

    public NotSubjectRevisionAuthorException(String message) {
        super(message);
    }
}
