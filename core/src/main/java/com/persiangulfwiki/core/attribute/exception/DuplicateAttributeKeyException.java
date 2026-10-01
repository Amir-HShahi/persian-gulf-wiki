package com.persiangulfwiki.core.attribute.exception;

import com.persiangulfwiki.core.common.exception.ConflictException;

public class DuplicateAttributeKeyException extends ConflictException {

    private static final String DEFAULT_MESSAGE = "attribute definition key already exists";

    public DuplicateAttributeKeyException() {
        super(DEFAULT_MESSAGE);
    }

    public DuplicateAttributeKeyException(String message) {
        super(message);
    }
}
