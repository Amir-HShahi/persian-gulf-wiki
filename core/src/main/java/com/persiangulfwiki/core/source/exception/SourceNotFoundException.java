package com.persiangulfwiki.core.source.exception;

import com.persiangulfwiki.core.common.exception.NotFoundException;

import java.io.Serial;

public class SourceNotFoundException extends NotFoundException {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String DEFAULT_MESSAGE = "source not found";

    public SourceNotFoundException() {
        super(DEFAULT_MESSAGE);
    }

    public SourceNotFoundException(String message) {
        super(message);
    }
}
