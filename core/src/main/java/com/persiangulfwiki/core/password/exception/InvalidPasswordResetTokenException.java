package com.persiangulfwiki.core.password.exception;

import java.io.Serial;

public class InvalidPasswordResetTokenException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String DEFAULT_MESSAGE = "invalid password reset token";

    public InvalidPasswordResetTokenException() {
        super(DEFAULT_MESSAGE);
    }

    public InvalidPasswordResetTokenException(String message) {
        super(message);
    }
}
