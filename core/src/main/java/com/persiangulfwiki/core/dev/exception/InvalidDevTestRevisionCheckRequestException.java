package com.persiangulfwiki.core.dev.exception;

import com.persiangulfwiki.core.common.exception.BadRequestException;

import java.io.Serial;

// A fixture request for a revision check without the one thing it cannot default: the revision.
public class InvalidDevTestRevisionCheckRequestException extends BadRequestException {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String DEFAULT_MESSAGE = "invalid test revision check request";

    public InvalidDevTestRevisionCheckRequestException() {
        super(DEFAULT_MESSAGE);
    }

    public InvalidDevTestRevisionCheckRequestException(String message) {
        super(message);
    }
}
