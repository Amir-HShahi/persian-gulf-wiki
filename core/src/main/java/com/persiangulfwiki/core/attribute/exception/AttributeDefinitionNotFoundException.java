package com.persiangulfwiki.core.attribute.exception;

import com.persiangulfwiki.core.common.exception.NotFoundException;

import java.io.Serial;

public class AttributeDefinitionNotFoundException extends NotFoundException {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String DEFAULT_MESSAGE = "attribute definition not found";

    public AttributeDefinitionNotFoundException() {
        super(DEFAULT_MESSAGE);
    }

    public AttributeDefinitionNotFoundException(String message) {
        super(message);
    }
}
