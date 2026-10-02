package com.persiangulfwiki.core.attribute.exception;

import com.persiangulfwiki.core.common.exception.ConflictException;

import java.io.Serial;

public class AttributeOptionsNotSupportedException extends ConflictException {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String DEFAULT_MESSAGE = "only ENUM definitions have options";

    public AttributeOptionsNotSupportedException() {
        super(DEFAULT_MESSAGE);
    }

    public AttributeOptionsNotSupportedException(String message) {
        super(message);
    }
}
