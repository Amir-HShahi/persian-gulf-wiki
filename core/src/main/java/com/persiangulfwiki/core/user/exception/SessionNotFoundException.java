package com.persiangulfwiki.core.user.exception;

import com.persiangulfwiki.core.common.exception.NotFoundException;

public class SessionNotFoundException extends NotFoundException {

    private static final String DEFAULT_MESSAGE = "session not found";

    public SessionNotFoundException() {
        super(DEFAULT_MESSAGE);
    }

    public SessionNotFoundException(String message) {
        super(message);
    }
}
