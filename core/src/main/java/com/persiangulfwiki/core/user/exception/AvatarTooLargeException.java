package com.persiangulfwiki.core.user.exception;

import com.persiangulfwiki.core.common.exception.BadRequestException;

import java.io.Serial;

public class AvatarTooLargeException extends BadRequestException {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String DEFAULT_MESSAGE = "avatar file too large";

    public AvatarTooLargeException() {
        super(DEFAULT_MESSAGE);
    }

    public AvatarTooLargeException(String message) {
        super(message);
    }
}
