package com.persiangulfwiki.core.subject.exception;

import com.persiangulfwiki.core.common.exception.BadRequestException;

public class InvalidSubjectKindException extends BadRequestException {

    private static final String DEFAULT_MESSAGE = "invalid subject kind";

    public InvalidSubjectKindException() {
        super(DEFAULT_MESSAGE);
    }

    public InvalidSubjectKindException(String message) {
        super(message);
    }
}
