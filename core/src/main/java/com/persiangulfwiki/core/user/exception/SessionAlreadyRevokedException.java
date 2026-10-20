package com.persiangulfwiki.core.user.exception;

import com.persiangulfwiki.core.common.exception.ConflictException;

import java.io.Serial;

public class SessionAlreadyRevokedException extends ConflictException {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String DEFAULT_MESSAGE = "session already revoked";

    public SessionAlreadyRevokedException() {
        super(DEFAULT_MESSAGE);
    }

    public SessionAlreadyRevokedException(String message) {
        super(message);
    }
}
