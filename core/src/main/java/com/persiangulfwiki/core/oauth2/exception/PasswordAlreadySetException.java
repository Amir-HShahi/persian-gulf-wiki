package com.persiangulfwiki.core.oauth2.exception;

import java.io.Serial;

public class PasswordAlreadySetException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String DEFAULT_MESSAGE = "password already set for this account";

    public PasswordAlreadySetException() {
        super(DEFAULT_MESSAGE);
    }
}
