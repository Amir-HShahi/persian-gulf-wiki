package com.persiangulfwiki.core.attribute.exception;

import com.persiangulfwiki.core.common.exception.ConflictException;

public class AttributeHasOptionsException extends ConflictException {

    private static final String DEFAULT_MESSAGE = "an ENUM definition with options cannot change value type";

    public AttributeHasOptionsException() {
        super(DEFAULT_MESSAGE);
    }

    public AttributeHasOptionsException(String message) {
        super(message);
    }
}
