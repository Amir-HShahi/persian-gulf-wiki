package com.persiangulfwiki.core.oauth2.exception;

public class PasswordAlreadySetException extends RuntimeException {

    private static final String DEFAULT_MESSAGE = "password already set for this account";

    public PasswordAlreadySetException() {
        super(DEFAULT_MESSAGE);
    }
}
