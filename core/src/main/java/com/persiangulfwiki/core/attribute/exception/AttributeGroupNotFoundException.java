package com.persiangulfwiki.core.attribute.exception;

import com.persiangulfwiki.core.common.exception.NotFoundException;

public class AttributeGroupNotFoundException extends NotFoundException {

    private static final String DEFAULT_MESSAGE = "attribute group not found";

    public AttributeGroupNotFoundException() {
        super(DEFAULT_MESSAGE);
    }

    public AttributeGroupNotFoundException(String message) {
        super(message);
    }
}
