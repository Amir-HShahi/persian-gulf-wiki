package com.persiangulfwiki.core.source.exception;

import com.persiangulfwiki.core.common.exception.NotFoundException;

public class SourceNotFoundException extends NotFoundException {

    private static final String DEFAULT_MESSAGE = "source not found";

    public SourceNotFoundException() {
        super(DEFAULT_MESSAGE);
    }

    public SourceNotFoundException(String message) {
        super(message);
    }
}
