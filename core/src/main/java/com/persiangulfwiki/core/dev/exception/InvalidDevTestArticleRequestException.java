package com.persiangulfwiki.core.dev.exception;

import com.persiangulfwiki.core.common.exception.BadRequestException;

import java.io.Serial;

// A fixture request for a state the schema forbids outright, e.g. a main article with no subject.
public class InvalidDevTestArticleRequestException extends BadRequestException {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String DEFAULT_MESSAGE = "invalid dev test article request";

    public InvalidDevTestArticleRequestException() {
        super(DEFAULT_MESSAGE);
    }

    public InvalidDevTestArticleRequestException(String message) {
        super(message);
    }
}
