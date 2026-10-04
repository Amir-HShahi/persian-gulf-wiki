package com.persiangulfwiki.core.attribute.exception;

import com.persiangulfwiki.core.common.exception.ConflictException;

import java.io.Serial;

public class AttributeIsDerivedException extends ConflictException {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String DEFAULT_MESSAGE = "attribute is derived";

    public AttributeIsDerivedException() {
        super(DEFAULT_MESSAGE);
    }

    public AttributeIsDerivedException(String message) {
        super(message);
    }
}
