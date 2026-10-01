package com.persiangulfwiki.core.attribute.exception;

import com.persiangulfwiki.core.common.exception.ConflictException;

public class AttributeOptionsNotSupportedException extends ConflictException {

    private static final String DEFAULT_MESSAGE = "only ENUM definitions have options";

    public AttributeOptionsNotSupportedException() {
        super(DEFAULT_MESSAGE);
    }

    public AttributeOptionsNotSupportedException(String message) {
        super(message);
    }
}
