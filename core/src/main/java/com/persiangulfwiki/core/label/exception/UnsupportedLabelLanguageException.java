package com.persiangulfwiki.core.label.exception;

import com.persiangulfwiki.core.common.exception.BadRequestException;

public class UnsupportedLabelLanguageException extends BadRequestException {

    private static final String DEFAULT_MESSAGE = "label language must be fa, en or ar";

    public UnsupportedLabelLanguageException() {
        super(DEFAULT_MESSAGE);
    }

    public UnsupportedLabelLanguageException(String message) {
        super(message);
    }
}
