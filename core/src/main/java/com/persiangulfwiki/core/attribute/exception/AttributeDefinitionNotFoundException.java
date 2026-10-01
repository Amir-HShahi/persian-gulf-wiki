package com.persiangulfwiki.core.attribute.exception;

import com.persiangulfwiki.core.common.exception.NotFoundException;

public class AttributeDefinitionNotFoundException extends NotFoundException {

    private static final String DEFAULT_MESSAGE = "attribute definition not found";

    public AttributeDefinitionNotFoundException() {
        super(DEFAULT_MESSAGE);
    }

    public AttributeDefinitionNotFoundException(String message) {
        super(message);
    }
}
