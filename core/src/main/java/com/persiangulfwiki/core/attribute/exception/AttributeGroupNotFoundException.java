package com.persiangulfwiki.core.attribute.exception;

import com.persiangulfwiki.core.common.exception.NotFoundException;

import java.io.Serial;

public class AttributeGroupNotFoundException extends NotFoundException {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String DEFAULT_MESSAGE = "attribute group not found";

    public AttributeGroupNotFoundException() {
        super(DEFAULT_MESSAGE);
    }

    public AttributeGroupNotFoundException(String message) {
        super(message);
    }
}
