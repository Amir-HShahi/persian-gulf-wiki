package com.persiangulfwiki.core.auth.exception;

public class InvalidCredentialsException extends RuntimeException {

    private static final String DEFAULT_MESSAGE = "invalid credentials";

    public InvalidCredentialsException() {
        super(DEFAULT_MESSAGE);
    }

    public InvalidCredentialsException(String message) {
        super(message);
    }
}
