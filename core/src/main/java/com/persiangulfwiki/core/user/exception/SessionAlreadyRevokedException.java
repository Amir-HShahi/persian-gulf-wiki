package com.persiangulfwiki.core.user.exception;

import com.persiangulfwiki.core.common.exception.ConflictException;

public class SessionAlreadyRevokedException extends ConflictException {

    private static final String DEFAULT_MESSAGE = "session already revoked";

    public SessionAlreadyRevokedException() {
        super(DEFAULT_MESSAGE);
    }

    public SessionAlreadyRevokedException(String message) {
        super(message);
    }
}
