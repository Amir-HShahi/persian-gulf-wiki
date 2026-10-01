package com.persiangulfwiki.core.attribute.exception;

import com.persiangulfwiki.core.common.exception.NotFoundException;

public class AttributeOptionNotFoundException extends NotFoundException {

    private static final String DEFAULT_MESSAGE = "attribute option not found";

    public AttributeOptionNotFoundException() {
        super(DEFAULT_MESSAGE);
    }

    public AttributeOptionNotFoundException(String message) {
        super(message);
    }
}
