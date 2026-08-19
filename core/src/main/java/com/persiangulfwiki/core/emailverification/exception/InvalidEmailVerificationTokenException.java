package com.persiangulfwiki.core.emailverification.exception;

public class InvalidEmailVerificationTokenException extends RuntimeException {

    private static final String DEFAULT_MESSAGE = "invalid email verification token";

    public InvalidEmailVerificationTokenException() {
        super(DEFAULT_MESSAGE);
    }

    public InvalidEmailVerificationTokenException(String message) {
        super(message);
    }
}
