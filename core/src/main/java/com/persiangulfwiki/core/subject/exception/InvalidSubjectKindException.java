package com.persiangulfwiki.core.subject.exception;

import com.persiangulfwiki.core.common.exception.BadRequestException;

import java.io.Serial;

public class InvalidSubjectKindException extends BadRequestException {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String DEFAULT_MESSAGE = "invalid subject kind";

    public InvalidSubjectKindException() {
        super(DEFAULT_MESSAGE);
    }

    public InvalidSubjectKindException(String message) {
        super(message);
    }
}
