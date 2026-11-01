package com.persiangulfwiki.core.user.exception;

import com.persiangulfwiki.core.common.exception.BadRequestException;

import java.io.Serial;

public class UnsupportedAvatarContentTypeException extends BadRequestException {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String DEFAULT_MESSAGE = "unsupported avatar content type";

    public UnsupportedAvatarContentTypeException() {
        super(DEFAULT_MESSAGE);
    }

    public UnsupportedAvatarContentTypeException(String message) {
        super(message);
    }
}
