package com.persiangulfwiki.core.dev.exception;

import com.persiangulfwiki.core.common.exception.BadRequestException;

import java.io.Serial;

// A fixture request asking for a state the real flow can never reach, e.g. an approved later
// metadata version on an item whose first version was never approved.
public class InvalidDevTestMediaRequestException extends BadRequestException {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String DEFAULT_MESSAGE = "invalid dev test media request";

    public InvalidDevTestMediaRequestException() {
        super(DEFAULT_MESSAGE);
    }

    public InvalidDevTestMediaRequestException(String message) {
        super(message);
    }
}
