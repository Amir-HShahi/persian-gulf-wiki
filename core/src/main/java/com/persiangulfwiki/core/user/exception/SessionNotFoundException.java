package com.persiangulfwiki.core.user.exception;

import com.persiangulfwiki.core.common.exception.NotFoundException;

import java.io.Serial;

public class SessionNotFoundException extends NotFoundException {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String DEFAULT_MESSAGE = "session not found";

    public SessionNotFoundException() {
        super(DEFAULT_MESSAGE);
    }

    public SessionNotFoundException(String message) {
        super(message);
    }
}
