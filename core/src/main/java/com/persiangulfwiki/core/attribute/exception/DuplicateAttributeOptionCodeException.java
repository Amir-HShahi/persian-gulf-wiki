package com.persiangulfwiki.core.attribute.exception;

import com.persiangulfwiki.core.common.exception.ConflictException;

public class DuplicateAttributeOptionCodeException extends ConflictException {

    private static final String DEFAULT_MESSAGE = "attribute option code already exists for this definition";

    public DuplicateAttributeOptionCodeException() {
        super(DEFAULT_MESSAGE);
    }

    public DuplicateAttributeOptionCodeException(String message) {
        super(message);
    }
}
