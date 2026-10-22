package com.persiangulfwiki.core.attribute.exception;

import com.persiangulfwiki.core.common.exception.ConflictException;

import java.io.Serial;

public class AttributeHasOptionsException extends ConflictException {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String DEFAULT_MESSAGE = "an ENUM definition with options cannot change value type";

    public AttributeHasOptionsException() {
        super(DEFAULT_MESSAGE);
    }

    public AttributeHasOptionsException(String message) {
        super(message);
    }
}
