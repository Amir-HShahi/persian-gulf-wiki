package com.persiangulfwiki.core.password.exception;

public class InvalidPasswordResetTokenException extends RuntimeException {

    private static final String DEFAULT_MESSAGE = "invalid password reset token";

    public InvalidPasswordResetTokenException() {
        super(DEFAULT_MESSAGE);
    }

    public InvalidPasswordResetTokenException(String message) {
        super(message);
    }
}
