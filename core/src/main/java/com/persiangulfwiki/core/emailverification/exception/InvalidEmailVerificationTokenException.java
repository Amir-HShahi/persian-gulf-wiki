package com.persiangulfwiki.core.emailverification.exception;

import java.io.Serial;

public class InvalidEmailVerificationTokenException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String DEFAULT_MESSAGE = "invalid email verification token";

    public InvalidEmailVerificationTokenException() {
        super(DEFAULT_MESSAGE);
    }

    public InvalidEmailVerificationTokenException(String message) {
        super(message);
    }
}
