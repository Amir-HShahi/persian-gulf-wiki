package com.persiangulfwiki.core.attribute.exception;

import com.persiangulfwiki.core.common.exception.ConflictException;

import java.io.Serial;

public class DuplicateAttributeOptionCodeException extends ConflictException {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String DEFAULT_MESSAGE = "attribute option code already exists for this definition";

    public DuplicateAttributeOptionCodeException() {
        super(DEFAULT_MESSAGE);
    }

    public DuplicateAttributeOptionCodeException(String message) {
        super(message);
    }
}
