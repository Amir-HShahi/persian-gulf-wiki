package com.persiangulfwiki.core.subject.exception;

import com.persiangulfwiki.core.common.exception.ForbiddenException;

import java.io.Serial;

public class NotSubjectKindExpertException extends ForbiddenException {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String DEFAULT_MESSAGE = "caller holds no expert reviewer role for this subject kind";

    public NotSubjectKindExpertException() {
        super(DEFAULT_MESSAGE);
    }

    public NotSubjectKindExpertException(String message) {
        super(message);
    }
}
