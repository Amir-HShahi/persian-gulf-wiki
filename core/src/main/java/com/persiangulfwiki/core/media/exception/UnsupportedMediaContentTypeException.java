package com.persiangulfwiki.core.media.exception;

import com.persiangulfwiki.core.common.exception.BadRequestException;

public class UnsupportedMediaContentTypeException extends BadRequestException {

    private static final String DEFAULT_MESSAGE = "content type not allowed for this media type";

    public UnsupportedMediaContentTypeException() {
        super(DEFAULT_MESSAGE);
    }

    public UnsupportedMediaContentTypeException(String message) {
        super(message);
    }
}
