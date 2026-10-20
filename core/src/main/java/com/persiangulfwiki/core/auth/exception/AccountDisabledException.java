package com.persiangulfwiki.core.auth.exception;

import java.io.Serial;

public class AccountDisabledException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String DEFAULT_MESSAGE = "account is disabled";

    public AccountDisabledException() {
        super(DEFAULT_MESSAGE);
    }

    public AccountDisabledException(String message) {
        super(message);
    }
}
