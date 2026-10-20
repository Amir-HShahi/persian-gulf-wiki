package com.persiangulfwiki.core.media.exception;

import com.persiangulfwiki.core.common.exception.BadRequestException;

import java.io.Serial;

public class UnsupportedMediaContentTypeException extends BadRequestException {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String DEFAULT_MESSAGE = "content type not allowed for this media type";

    public UnsupportedMediaContentTypeException() {
        super(DEFAULT_MESSAGE);
    }

    public UnsupportedMediaContentTypeException(String message) {
        super(message);
    }
}
