package com.persiangulfwiki.core.auth.exception;

import java.io.Serial;

public class InvalidRefreshTokenException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String DEFAULT_MESSAGE = "invalid refresh token";

    public InvalidRefreshTokenException() {
        super(DEFAULT_MESSAGE);
    }

    public InvalidRefreshTokenException(String message) {
        super(message);
    }
}
