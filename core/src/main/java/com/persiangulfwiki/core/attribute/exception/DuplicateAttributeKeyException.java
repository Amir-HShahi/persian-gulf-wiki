package com.persiangulfwiki.core.attribute.exception;

import com.persiangulfwiki.core.common.exception.ConflictException;

import java.io.Serial;

public class DuplicateAttributeKeyException extends ConflictException {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String DEFAULT_MESSAGE = "attribute definition key already exists";

    public DuplicateAttributeKeyException() {
        super(DEFAULT_MESSAGE);
    }

    public DuplicateAttributeKeyException(String message) {
        super(message);
    }
}
