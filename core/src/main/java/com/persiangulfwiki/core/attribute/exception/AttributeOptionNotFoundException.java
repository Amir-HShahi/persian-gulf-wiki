package com.persiangulfwiki.core.attribute.exception;

import com.persiangulfwiki.core.common.exception.NotFoundException;

import java.io.Serial;

public class AttributeOptionNotFoundException extends NotFoundException {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String DEFAULT_MESSAGE = "attribute option not found";

    public AttributeOptionNotFoundException() {
        super(DEFAULT_MESSAGE);
    }

    public AttributeOptionNotFoundException(String message) {
        super(message);
    }
}
