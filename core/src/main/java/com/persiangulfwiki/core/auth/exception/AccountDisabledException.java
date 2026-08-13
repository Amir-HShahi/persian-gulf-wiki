package com.persiangulfwiki.core.auth.exception;

public class AccountDisabledException extends RuntimeException {

    private static final String DEFAULT_MESSAGE = "account is disabled";

    public AccountDisabledException() {
        super(DEFAULT_MESSAGE);
    }

    public AccountDisabledException(String message) {
        super(message);
    }
}
