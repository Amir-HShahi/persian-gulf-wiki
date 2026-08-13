package com.persiangulfwiki.core.auth.exception;

public class InvalidRefreshTokenException extends RuntimeException {

    private static final String DEFAULT_MESSAGE = "invalid refresh token";

    public InvalidRefreshTokenException() {
        super(DEFAULT_MESSAGE);
    }

    public InvalidRefreshTokenException(String message) {
        super(message);
    }
}
