package com.persiangulfwiki.core.subject.exception;

import com.persiangulfwiki.core.common.exception.BadRequestException;

public class InvalidGeometryException extends BadRequestException {

    private static final String DEFAULT_MESSAGE = "invalid geometry";

    public InvalidGeometryException() {
        super(DEFAULT_MESSAGE);
    }

    public InvalidGeometryException(String message) {
        super(message);
    }
}
