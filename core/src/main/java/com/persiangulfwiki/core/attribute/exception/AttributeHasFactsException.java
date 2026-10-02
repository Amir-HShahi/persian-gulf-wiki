package com.persiangulfwiki.core.attribute.exception;

import com.persiangulfwiki.core.common.exception.ConflictException;

public class AttributeHasFactsException extends ConflictException {

    private static final String DEFAULT_MESSAGE = "attribute definition already has facts";

    public AttributeHasFactsException() {
        super(DEFAULT_MESSAGE);
    }

    public AttributeHasFactsException(String message) {
        super(message);
    }
}
