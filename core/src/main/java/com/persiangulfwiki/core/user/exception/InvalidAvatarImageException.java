package com.persiangulfwiki.core.user.exception;

import com.persiangulfwiki.core.common.exception.BadRequestException;

import java.io.Serial;

public class InvalidAvatarImageException extends BadRequestException {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String DEFAULT_MESSAGE = "avatar is not a readable image within the size limits";

    public InvalidAvatarImageException() {
        super(DEFAULT_MESSAGE);
    }

    public InvalidAvatarImageException(String message) {
        super(message);
    }
}
