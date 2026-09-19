package com.persiangulfwiki.core.subject.exception;

import com.persiangulfwiki.core.common.exception.BadRequestException;

public class SubjectKindMismatchException extends BadRequestException {

    private static final String DEFAULT_MESSAGE = "field does not belong to this subject kind";

    public SubjectKindMismatchException() {
        super(DEFAULT_MESSAGE);
    }

    public SubjectKindMismatchException(String message) {
        super(message);
    }
}
