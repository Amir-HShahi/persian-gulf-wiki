package com.persiangulfwiki.core.attribute.exception;

import com.persiangulfwiki.core.common.exception.ConflictException;

public class DuplicateAttributeGroupKeyException extends ConflictException {

    private static final String DEFAULT_MESSAGE = "attribute group key already exists";

    public DuplicateAttributeGroupKeyException() {
        super(DEFAULT_MESSAGE);
    }

    public DuplicateAttributeGroupKeyException(String message) {
        super(message);
    }
}
